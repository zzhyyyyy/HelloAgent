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
import org.springframework.ai.chat.model.Generation;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
            List<Message> currentMessages = chatMemory.get(sessionId);
            // 过滤孤立 ToolResponseMessage，避免 DeepSeek HTTP 400
            currentMessages = stripOrphanToolResponses(currentMessages);
            Prompt prompt = Prompt.builder()
                    .chatOptions(chatOptions)
                    .messages(currentMessages)
                    .build();

            // —— 流式调用:边收 chunk 边推 SSE(与 ShopAi.think() 同构)
            final String streamId = UUID.randomUUID().toString();
            final StringBuilder accumulated = new StringBuilder();
            final AtomicBoolean didStream = new AtomicBoolean(false);

            List<ChatResponse> chunks = chatClient.prompt(prompt)
                    .toolCallbacks(toolCallbacks.toArray(new ToolCallback[0]))
                    .stream()
                    .chatResponse()
                    .doOnNext(resp -> {
                        if (resp == null || resp.getResult() == null) return;
                        AssistantMessage out = resp.getResult().getOutput();
                        if (out == null) return;
                        String delta = out.getText();
                        if (delta != null && !delta.isEmpty()) {
                            accumulated.append(delta);
                            didStream.set(true);
                            emitStreamChunk(sessionId, agentRole, streamId,
                                    accumulated.toString(), false, null, null, null, sseService);
                        }
                    })
                    .collectList()
                    .block();

            Assert.notNull(chunks, "Specialist streaming chunks cannot be null");
            if (chunks.isEmpty()) {
                throw new IllegalStateException("Specialist LLM 流式响应为空");
            }

            ChatResponse last = chunks.get(chunks.size() - 1);
            List<AssistantMessage.ToolCall> mergedToolCalls = mergeToolCalls(chunks);
            AssistantMessage output = AssistantMessage.builder()
                    .content(accumulated.toString())
                    .toolCalls(mergedToolCalls)
                    .build();
            ChatResponse response = new ChatResponse(
                    List.of(new Generation(output, last.getResult().getMetadata())),
                    last.getMetadata()
            );

            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

            // 抽 token 用量(usage 通常只在最后一帧带回)
            ChatMessageDTO.Usage usage = extractUsage(last);

            // 持久化 + 前端同步
            if (didStream.get()) {
                String persistedId = persistStreamedAssistant(sessionId, agentRole,
                        accumulated.toString(), mergedToolCalls, usage,
                        chatMessageFacadeService);
                emitStreamChunk(sessionId, agentRole, streamId,
                        accumulated.toString(), true, persistedId, mergedToolCalls, usage, sseService);
            } else {
                // 只有 tool_calls、无文本:走原来的 AI_GENERATED_CONTENT 路径
                saveAndPushMessage(sessionId, output, agentRole, usage, chatMessageFacadeService, chatMessageConverter, sseService);
            }

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
                                          ChatMessageDTO.Usage usage,
                                          ChatMessageFacadeService chatMessageFacadeService,
                                          ChatMessageConverter chatMessageConverter,
                                          SseService sseService) {
        ChatMessageDTO.MetaData metadata = ChatMessageDTO.MetaData.builder()
                .toolCalls(message.getToolCalls())
                .agentRole(agentRole)
                .usage(usage)
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

    // ========== 流式辅助(与 ShopAi.think 同构) ==========

    /** 从 ChatResponse.getMetadata().getUsage() 抽 token 用量。usage 缺失时返回 null。 */
    private static ChatMessageDTO.Usage extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return null;
        org.springframework.ai.chat.metadata.Usage u = response.getMetadata().getUsage();
        if (u == null) return null;
        Integer prompt = u.getPromptTokens();
        Integer completion = u.getCompletionTokens();
        Integer total = u.getTotalTokens();
        String model = response.getMetadata().getModel();
        String finishReason = response.getResult() != null && response.getResult().getMetadata() != null
                ? response.getResult().getMetadata().getFinishReason()
                : null;
        return ChatMessageDTO.Usage.builder()
                .promptTokens(prompt == null ? null : prompt.longValue())
                .completionTokens(completion == null ? null : completion.longValue())
                .totalTokens(total == null ? null : total.longValue())
                .model(model)
                .finishReason(finishReason)
                .build();
    }

    /** 合并所有 chunk 的 tool_calls,按 id 去重。 */
    private static List<AssistantMessage.ToolCall> mergeToolCalls(List<ChatResponse> chunks) {
        List<AssistantMessage.ToolCall> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChatResponse r : chunks) {
            if (r == null || r.getResult() == null || r.getResult().getOutput() == null) continue;
            List<AssistantMessage.ToolCall> tc = r.getResult().getOutput().getToolCalls();
            if (tc == null) continue;
            for (AssistantMessage.ToolCall c : tc) {
                String id = c.id();
                if (id != null && !seen.add(id)) continue;
                merged.add(c);
            }
        }
        return merged;
    }

    /** 仅持久化流式 assistant(不再推 AI_GENERATED_CONTENT),返回 DB id。 */
    private static String persistStreamedAssistant(String sessionId, String agentRole,
                                                   String finalContent,
                                                   List<AssistantMessage.ToolCall> toolCalls,
                                                   ChatMessageDTO.Usage usage,
                                                   ChatMessageFacadeService chatMessageFacadeService) {
        ChatMessageDTO.MetaData metadata = ChatMessageDTO.MetaData.builder()
                .toolCalls(toolCalls)
                .agentRole(agentRole)
                .usage(usage)
                .build();
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(finalContent)
                .sessionId(sessionId)
                .metadata(metadata)
                .build();
        CreateChatMessageResponse resp = chatMessageFacadeService.createChatMessage(dto);
        return resp.getChatMessageId();
    }

    /** 推送一帧 AI_STREAM_CHUNK。 */
    private static void emitStreamChunk(String sessionId, String agentRole, String streamId,
                                        String content, boolean done, String persistedId,
                                        List<AssistantMessage.ToolCall> toolCalls,
                                        ChatMessageDTO.Usage usage,
                                        SseService sseService) {
        ChatMessageDTO.MetaData metadata = ChatMessageDTO.MetaData.builder()
                .toolCalls(toolCalls)
                .agentRole(agentRole)
                .usage(usage)
                .build();
        ChatMessageVO vo = ChatMessageVO.builder()
                .id(streamId)
                .sessionId(sessionId)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .metadata(metadata)
                .build();
        sseService.send(sessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_STREAM_CHUNK)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .done(done)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(persistedId)
                        .build())
                .build());
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

    /**
     * 过滤孤立 ToolResponseMessage，确保每个 tool 消息前面都有带 tool_calls 的 assistant 消息。
     * DeepSeek API 严格要求此协议，否则返回 HTTP 400。
     */
    private static List<Message> stripOrphanToolResponses(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        boolean lastWasAssistantWithToolCalls = false;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage) {
                if (!lastWasAssistantWithToolCalls) {
                    log.warn("SpecialistRunner 过滤孤立 ToolResponseMessage");
                    continue;
                }
                result.add(msg);
                lastWasAssistantWithToolCalls = false;
            } else {
                result.add(msg);
                lastWasAssistantWithToolCalls = (msg instanceof AssistantMessage am)
                        && am.getToolCalls() != null && !am.getToolCalls().isEmpty();
            }
        }
        return result;
    }
}
