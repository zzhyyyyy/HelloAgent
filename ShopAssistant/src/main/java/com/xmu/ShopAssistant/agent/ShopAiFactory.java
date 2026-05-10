package com.xmu.ShopAssistant.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xmu.ShopAssistant.agent.multi.DelegateToSpecialistTool;
import com.xmu.ShopAssistant.agent.tools.Tool;
import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.converter.AgentConverter;
import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.converter.KnowledgeBaseConverter;
import com.xmu.ShopAssistant.mapper.AgentMapper;
import com.xmu.ShopAssistant.mapper.KnowledgeBaseMapper;
import com.xmu.ShopAssistant.model.dto.AgentDTO;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.dto.KnowledgeBaseDTO;
import com.xmu.ShopAssistant.model.entity.Agent;
import com.xmu.ShopAssistant.model.entity.KnowledgeBase;
import com.xmu.ShopAssistant.service.AgentMemoryService;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SessionSummaryService;
import com.xmu.ShopAssistant.service.SpecialistRunner;
import com.xmu.ShopAssistant.service.SseService;
import com.xmu.ShopAssistant.service.ToolFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


@Component
public class ShopAiFactory {

    private static final Logger log = LoggerFactory.getLogger(ShopAiFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final AgentMemoryService agentMemoryService;
    private final SessionSummaryService sessionSummaryService;
    private final SpecialistRunner specialistRunner;

    // 运行时 Agent 配置
    private AgentDTO agentConfig;

    public ShopAiFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            AgentMemoryService agentMemoryService,
            SessionSummaryService sessionSummaryService,
            SpecialistRunner specialistRunner
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.agentMemoryService = agentMemoryService;
        this.sessionSummaryService = sessionSummaryService;
        this.specialistRunner = specialistRunner;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        List<Message> memory = new ArrayList<>();

        String sessionSummary = sessionSummaryService.getSessionSummary(chatSessionId);
        if (StringUtils.hasLength(sessionSummary)) {
            memory.add(new SystemMessage("""
                    【会话滚动摘要】
                    %s
                    """.formatted(sessionSummary)));
        }

        memory.addAll(rebuildProtocolSafeMessages(chatMessages));
        return memory;
    }

    private List<Message> rebuildProtocolSafeMessages(List<ChatMessageDTO> chatMessages) {
        List<Message> messages = new ArrayList<>();
        if (chatMessages == null || chatMessages.isEmpty()) {
            return messages;
        }

        for (int i = 0; i < chatMessages.size(); i++) {
            ChatMessageDTO dto = chatMessages.get(i);
            if (dto == null || dto.getRole() == null) {
                continue;
            }

            switch (dto.getRole()) {
                case SYSTEM:
                    if (StringUtils.hasText(dto.getContent())) {
                        messages.add(new SystemMessage(dto.getContent()));
                    }
                    break;
                case USER:
                    if (StringUtils.hasText(dto.getContent())) {
                        messages.add(new UserMessage(dto.getContent()));
                    }
                    break;
                case ASSISTANT:
                    List<AssistantMessage.ToolCall> toolCalls = dto.getMetadata() == null
                            ? List.of()
                            : dto.getMetadata().getToolCalls();
                    boolean hasToolCalls = toolCalls != null && !toolCalls.isEmpty();

                    if (!hasToolCalls) {
                        messages.add(AssistantMessage.builder()
                                .content(dto.getContent())
                                .build());
                        break;
                    }

                    List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                    int j = i + 1;
                    while (j < chatMessages.size()) {
                        ChatMessageDTO next = chatMessages.get(j);
                        if (next == null || next.getRole() != ChatMessageDTO.RoleType.TOOL) {
                            break;
                        }
                        if (next.getMetadata() != null && next.getMetadata().getToolResponse() != null) {
                            responses.add(next.getMetadata().getToolResponse());
                        }
                        j++;
                    }

                    if (responses.isEmpty()) {
                        // 避免向模型发送不完整的 tool 协议片段
                        log.warn("跳过未配对的 assistant(tool_calls) 消息，index={}", i);
                        break;
                    }

                    messages.add(AssistantMessage.builder()
                            .content(dto.getContent())
                            .toolCalls(toolCalls)
                            .build());
                    messages.add(ToolResponseMessage.builder()
                            .responses(responses)
                            .build());
                    i = j - 1;
                    break;
                case TOOL:
                    // 只接受由 ASSISTANT(tool_calls) 驱动的 TOOL 响应，孤立 TOOL 直接丢弃
                    log.warn("跳过孤立 tool 消息，index={}", i);
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            dto.getRole().getRole(),
                            dto.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return messages;
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        // 固定工具（系统强制）
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        // 可选工具（按 Agent 配置）
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
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private ShopAi buildAgentRuntime(
            Agent agent,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        String mergedSystemPrompt = mergeSystemPromptWithLongTermMemory(agent.getSystemPrompt(), agent.getId());
        return new ShopAi(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                mergedSystemPrompt,
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter
        );
    }

    private String mergeSystemPromptWithLongTermMemory(String systemPrompt, String agentId) {
        List<String> facts = agentMemoryService.listFactsByAgentId(agentId, 20);
        if (facts.isEmpty()) {
            return systemPrompt;
        }
        String memoryPrompt = facts.stream()
                .map(f -> "- " + f)
                .collect(Collectors.joining("\n"));
        String addon = """
                【跨会话用户画像记忆】
                以下信息来自历史会话，可用于个性化回答；若与用户当前明确表述冲突，以当前会话为准：
                %s
                """.formatted(memoryPrompt);

        if (!StringUtils.hasText(systemPrompt)) {
            return addon;
        }
        return systemPrompt + "\n\n" + addon;
    }

    /**
     * 创建一个 ShopAi 实例
     */
    public ShopAi create(String agentId, String chatSessionId) {
        Agent agent = loadAgent(agentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        log.info("加载chatSessionId记忆");
        List<Message> memory = loadMemory(chatSessionId);
        log.info("加载chatSessionId记忆成功");
        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        return buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId
        );
    }

    /**
     * 创建一个 Supervisor 模式的 ShopAi 实例（多 Agent 编排）。
     * Supervisor 拥有用户 Agent 的所有工具 + DelegateToSpecialistTool，
     * 可将子任务委派给其他 Specialist Agent 执行。
     */
    public ShopAi createSupervisor(String userAgentId, String chatSessionId) {
        Agent agent = loadAgent(userAgentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        log.info("加载 chatSessionId 记忆（Supervisor 模式）");
        List<Message> memory = loadMemory(chatSessionId);

        // 解析知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);

        // 解析用户 Agent 的工具，并追加 DelegateToSpecialistTool
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        DelegateToSpecialistTool delegateTool = new DelegateToSpecialistTool(
                chatSessionId,
                specialistRunner,
                chatMessageFacadeService,
                chatMessageConverter,
                sseService
        );
        runtimeTools.add(delegateTool);

        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        // 构建 Supervisor 系统提示词
        String supervisorSystemPrompt = buildSupervisorSystemPrompt(agent, agentConfig, knowledgeBases);

        // 获取 ChatClient
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (chatClient == null) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }

        String mergedPrompt = mergeSystemPromptWithLongTermMemory(supervisorSystemPrompt, agent.getId());

        int messageLength = agentConfig.getChatOptions() != null && agentConfig.getChatOptions().getMessageLength() != null
                ? agentConfig.getChatOptions().getMessageLength()
                : 10;

        ShopAi shopAi = new ShopAi(
                agent.getId(),
                "Supervisor",
                agent.getDescription(),
                mergedPrompt,
                chatClient,
                messageLength,
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter
        );
        shopAi.setAgentRole("supervisor");
        return shopAi;
    }

    /**
     * 构建 Supervisor 的系统提示词，包含可用的 Specialist 列表。
     */
    private String buildSupervisorSystemPrompt(Agent userAgent, AgentDTO agentConfig, List<KnowledgeBaseDTO> knowledgeBases) {
        // 收集所有其他 Agent 作为可用的 Specialist
        List<Agent> allAgents = agentMapper.selectAll();
        StringBuilder specialistList = new StringBuilder();
        for (Agent a : allAgents) {
            if (a.getId().equals(userAgent.getId())) {
                continue; // 排除自己
            }
            specialistList.append("- ")
                    .append(a.getId()).append(": ")
                    .append(a.getName() != null ? a.getName() : "未命名")
                    .append(" — ")
                    .append(a.getDescription() != null ? a.getDescription() : "无描述")
                    .append("\n");
        }

        String kbsInfo = knowledgeBases != null && !knowledgeBases.isEmpty()
                ? "你拥有的知识库列表：\n" + knowledgeBases.stream()
                    .map(kb -> "- " + kb.getId() + ": " + kb.getName())
                    .collect(java.util.stream.Collectors.joining("\n"))
                : "当前没有可用的知识库。";

        return """
                你是 ShopAssistant 的「任务协调员（Supervisor）」，负责协调多个专业 Agent 协作解决用户问题。

                【你的职责】
                1. 分析用户的复杂问题，将其拆解为若干子任务
                2. 使用 DelegateToSpecialist 工具将子任务委派给最合适的 Specialist Agent
                3. 收集所有 Specialist 的返回结果，综合整理后给出最终回答
                4. 对于简单问题，你也可以直接使用现有工具回答，无需委派

                【可用 Specialist Agent】
                %s

                【委派规则】
                - 每个子任务应尽量清晰、完整地描述上下文和目标
                - 可以并行委派多个 Specialist（依次调用工具）
                - Specialist 会独立执行任务并使用自己的工具
                - 收到 Specialist 返回结果后，需要整理汇总

                【知识库】
                %s

                【注意事项】
                - 用户的问题可能涉及多个领域，合理拆分任务
                - 如果 Specialist 返回的信息不足，可以追问或重新委派
                - 最终回答应整合所有 Specialist 的结果，给出完整的答案
                """.formatted(specialistList, kbsInfo);
    }
}
