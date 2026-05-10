package com.xmu.ShopAssistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.agent.tools.Tool;
import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.converter.AgentConverter;
import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.exception.BizException;
import com.xmu.ShopAssistant.mapper.AgentMapper;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.model.dto.AgentDTO;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.entity.Agent;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SpecialistRunner {

    private static final int MAX_STEPS = 15;
    private static final int DEFAULT_MAX_MESSAGES = 20;

    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final ChatClientRegistry chatClientRegistry;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final SseService sseService;
    private final ObjectMapper objectMapper;

    public SpecialistRunner(AgentMapper agentMapper,
                            AgentConverter agentConverter,
                            ChatClientRegistry chatClientRegistry,
                            ToolFacadeService toolFacadeService,
                            ChatMessageFacadeService chatMessageFacadeService,
                            ChatMessageConverter chatMessageConverter,
                            SseService sseService,
                            ObjectMapper objectMapper) {
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.chatClientRegistry = chatClientRegistry;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.sseService = sseService;
        this.objectMapper = objectMapper;
    }

    /**
     * 运行一个 Specialist Agent 的完整 ReAct 循环
     *
     * @param specialistAgentId  Specialist 的 agentId
     * @param task               委派的具体任务描述
     * @param sessionId          聊天会话 ID
     * @param agentRole          写入消息 metadata 的 agentRole 值
     * @return Specialist 的最终回答文本
     */
    public String run(String specialistAgentId, String task, String sessionId, String agentRole) {
        // 1. 加载 Specialist Agent 配置
        Agent agent = loadAgent(specialistAgentId);
        AgentDTO agentConfig = toAgentConfig(agent);

        // 2. 获取 ChatClient
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (chatClient == null) {
            throw new BizException("未找到 Specialist 对应的模型: " + agent.getModel());
        }

        // 3. 解析工具列表并构建 ToolCallback
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        // 4. 创建独立的 ChatMemory（不加载历史，只包含 system prompt + 委派任务）
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(DEFAULT_MAX_MESSAGES)
                .build();
        chatMemory.add(sessionId, new SystemMessage(agent.getSystemPrompt()));
        chatMemory.add(sessionId, new UserMessage(task));

        // 5. 运行 ReAct 循环
        ChatOptions chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();
        ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

        String finalAnswer = null;

        for (int step = 0; step < MAX_STEPS; step++) {
            // Think 阶段
            Prompt prompt = Prompt.builder()
                    .chatOptions(chatOptions)
                    .messages(chatMemory.get(sessionId))
                    .build();

            ChatResponse response = chatClient.prompt(prompt)
                    .toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]))
                    .call()
                    .chatClientResponse()
                    .chatResponse();

            Assert.notNull(response, "Specialist ChatResponse cannot be null");

            AssistantMessage output = response.getResult().getOutput();
            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

            // 保存 AssistantMessage（含 agentRole）
            saveAndPushMessage(sessionId, output, agentRole, chatMessageFacadeService, chatMessageConverter, sseService);

            if (!hasToolCalls(toolCalls)) {
                // 无工具调用，返回最终回答
                finalAnswer = output.getText();
                break;
            }

            // Execute 阶段
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            chatMemory.clear(sessionId);
            chatMemory.add(sessionId, result.conversationHistory());

            // 保存 ToolResponseMessage
            List<Message> history = result.conversationHistory();
            if (!history.isEmpty()) {
                Message lastMsg = history.get(history.size() - 1);
                if (lastMsg instanceof ToolResponseMessage toolResponseMsg) {
                    saveAndPushToolResponses(sessionId, toolResponseMsg, agentRole, chatMessageFacadeService, chatMessageConverter, sseService);

                    // 检查 terminate
                    if (toolResponseMsg.getResponses().stream().anyMatch(r -> "terminate".equals(r.name()))) {
                        log.info("Specialist 任务结束（terminate）: agentId={}", specialistAgentId);
                        break;
                    }
                }
            }
        }

        // 如果循环正常结束但没有 finalAnswer，从 memory 中取最后一条 AssistantMessage
        if (finalAnswer == null) {
            List<Message> msgs = chatMemory.get(sessionId);
            for (int i = msgs.size() - 1; i >= 0; i--) {
                if (msgs.get(i) instanceof AssistantMessage am && StringUtils.hasText(am.getText())) {
                    finalAnswer = am.getText();
                    break;
                }
            }
        }

        log.info("Specialist 执行完成: agentId={}, finalAnswerLength={}", specialistAgentId,
                finalAnswer != null ? finalAnswer.length() : 0);
        return finalAnswer != null ? finalAnswer : "任务执行完成";
    }

    // ========== 静态工具方法 ==========

    public static void saveAndPushMessage(String sessionId, AssistantMessage message, String agentRole,
                                          ChatMessageFacadeService chatMessageFacadeService,
                                          ChatMessageConverter chatMessageConverter,
                                          SseService sseService) {
        ChatMessageDTO.MetaData metadata = ChatMessageDTO.MetaData.builder()
                .toolCalls(message.getToolCalls())
                .agentRole(agentRole)
                .build();

        ChatMessageDTO dto = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(message.getText())
                .sessionId(sessionId)
                .metadata(metadata)
                .build();

        CreateChatMessageResponse resp = chatMessageFacadeService.createChatMessage(dto);
        dto.setId(resp.getChatMessageId());

        ChatMessageVO vo = chatMessageConverter.toVO(dto);
        sseService.send(sessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder().message(vo).build())
                .metadata(SseMessage.Metadata.builder().chatMessageId(dto.getId()).build())
                .build());
    }

    public static void saveAndPushToolResponses(String sessionId, ToolResponseMessage toolResponseMsg, String agentRole,
                                                ChatMessageFacadeService chatMessageFacadeService,
                                                ChatMessageConverter chatMessageConverter,
                                                SseService sseService) {
        for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMsg.getResponses()) {
            ChatMessageDTO.MetaData metadata = ChatMessageDTO.MetaData.builder()
                    .toolResponse(toolResponse)
                    .agentRole(agentRole)
                    .build();

            ChatMessageDTO dto = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.TOOL)
                    .content(toolResponse.responseData())
                    .sessionId(sessionId)
                    .metadata(metadata)
                    .build();

            CreateChatMessageResponse resp = chatMessageFacadeService.createChatMessage(dto);
            dto.setId(resp.getChatMessageId());

            ChatMessageVO vo = chatMessageConverter.toVO(dto);
            sseService.send(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder().message(vo).build())
                    .metadata(SseMessage.Metadata.builder().chatMessageId(dto.getId()).build())
                    .build());
        }
    }

    // ========== 私有方法 ==========

    private Agent loadAgent(String agentId) {
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BizException("Specialist Agent 不存在: " + agentId);
        }
        return agent;
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            return agentConverter.toDTO(agent);
        } catch (JsonProcessingException e) {
            throw new BizException("解析 Specialist Agent 配置失败: " + e.getMessage());
        }
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }
        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));
        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException("解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private boolean hasToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
