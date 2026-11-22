package com.xmu.ShopAssistant.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xmu.ShopAssistant.checkpoint.AgentCheckpointProperties;
import com.xmu.ShopAssistant.checkpoint.AgentCheckpointService;
import com.xmu.ShopAssistant.checkpoint.AgentRuntimeType;
import com.xmu.ShopAssistant.agent.multi.DelegateToSpecialistTool;
import com.xmu.ShopAssistant.agent.tools.Tool;
import com.xmu.ShopAssistant.agent.tools.security.SensitivityLevel;
import com.xmu.ShopAssistant.agent.loop.ToolLoopGuard;
import com.xmu.ShopAssistant.agent.loop.ToolLoopGuardFactory;
import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.converter.AgentConverter;
import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.converter.KnowledgeBaseConverter;
import com.xmu.ShopAssistant.eval.AgentEvalService;
import com.xmu.ShopAssistant.guardrails.GuardrailsService;
import com.xmu.ShopAssistant.mapper.AgentMapper;
import com.xmu.ShopAssistant.mapper.KnowledgeBaseMapper;
import com.xmu.ShopAssistant.memory.MemoryContextAssembler;
import com.xmu.ShopAssistant.model.dto.AgentDTO;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.dto.KnowledgeBaseDTO;
import com.xmu.ShopAssistant.model.entity.Agent;
import com.xmu.ShopAssistant.model.entity.KnowledgeBase;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.skill.SkillRegistry;
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
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MemoryContextAssembler memoryContextAssembler;
    private final SessionSummaryService sessionSummaryService;
    private final SpecialistRunner specialistRunner;
    private final PromptRegistry promptRegistry;
    private final SkillRegistry skillRegistry;
    /** 外部 ToolCallback 供应者(MCP server / 自定义集成等),Spring 自动注入所有实现 */
    private final List<ToolCallbackProvider> externalToolProviders;
    private final GuardrailsService guardrailsService;
    private final AgentEvalService agentEvalService;
    private final AgentCheckpointService checkpointService;
    private final AgentCheckpointProperties checkpointProperties;
    private final ToolLoopGuardFactory toolLoopGuardFactory;

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
            MemoryContextAssembler memoryContextAssembler,
            SessionSummaryService sessionSummaryService,
            SpecialistRunner specialistRunner,
            PromptRegistry promptRegistry,
            SkillRegistry skillRegistry,
            @Autowired(required = false) List<ToolCallbackProvider> externalToolProviders,
            GuardrailsService guardrailsService,
            AgentEvalService agentEvalService,
            AgentCheckpointService checkpointService,
            AgentCheckpointProperties checkpointProperties,
            ToolLoopGuardFactory toolLoopGuardFactory
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
        this.memoryContextAssembler = memoryContextAssembler;
        this.sessionSummaryService = sessionSummaryService;
        this.specialistRunner = specialistRunner;
        this.promptRegistry = promptRegistry;
        this.skillRegistry = skillRegistry;
        this.externalToolProviders = externalToolProviders == null ? List.of() : externalToolProviders;
        this.guardrailsService = guardrailsService;
        this.agentEvalService = agentEvalService;
        this.checkpointService = checkpointService;
        this.checkpointProperties = checkpointProperties;
        this.toolLoopGuardFactory = toolLoopGuardFactory;
    }

    /** 渐进式披露 · 第 1 层:在 system prompt 末尾追加 Skill 目录。 */
    private String mergeSystemPromptWithSkillCatalog(String systemPrompt) {
        if (skillRegistry == null || skillRegistry.all().isEmpty()) {
            return systemPrompt;
        }
        String catalog = """

                【可用 Skill 目录】(渐进式披露:仅看到 name+简介;如认为某 skill 适合当前任务,调用 useSkill(name) 获取完整流程)
                %s
                """.formatted(skillRegistry.catalogForPrompt());
        return systemPrompt == null || systemPrompt.isBlank() ? catalog : systemPrompt + "\n" + catalog;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId, String runtimeAgentRole) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> recent = chatMessageFacadeService.getChatMessagesBySessionIdRecently(
                chatSessionId,
                Math.max(messageLength, messageLength * 4)
        );
        List<ChatMessageDTO> roleSafeMessages = recent.stream()
                .filter(dto -> belongsToRuntime(dto, runtimeAgentRole))
                .toList();
        List<ChatMessageDTO> chatMessages = roleSafeMessages.size() <= messageLength
                ? roleSafeMessages
                : roleSafeMessages.subList(roleSafeMessages.size() - messageLength, roleSafeMessages.size());
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

    /** 多 Agent 消息共用一张表，恢复时必须隔离 Supervisor 与 Specialist 的工具协议。 */
    private boolean belongsToRuntime(ChatMessageDTO dto, String runtimeAgentRole) {
        if (dto == null || dto.getRole() == null) {
            return false;
        }
        if (dto.getRole() == ChatMessageDTO.RoleType.USER || dto.getRole() == ChatMessageDTO.RoleType.SYSTEM) {
            return true;
        }
        String persistedRole = dto.getMetadata() == null ? null : dto.getMetadata().getAgentRole();
        if (runtimeAgentRole == null) {
            return persistedRole == null;
        }
        return persistedRole == null || runtimeAgentRole.equals(persistedRole);
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

    private RuntimeToolCallbacks buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        Set<String> approvalRequiredNames = new LinkedHashSet<>();
        // 1) 来自 @Component Tool bean(项目内置)
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
            if (tool.getSensitivity() == SensitivityLevel.DANGEROUS) {
                Arrays.stream(toolCallbacks)
                        .map(callback -> callback.getToolDefinition().name())
                        .forEach(approvalRequiredNames::add);
            }
        }
        // 2) 来自外部 ToolCallbackProvider(MCP server / 自定义集成等)
        for (ToolCallbackProvider provider : externalToolProviders) {
            ToolCallback[] arr = provider.getToolCallbacks();
            if (arr != null && arr.length > 0) {
                callbacks.addAll(Arrays.asList(arr));
                log.info("已合并外部 ToolCallbackProvider: provider={} tools={}",
                        provider.getClass().getSimpleName(), arr.length);
            }
        }
        approvalRequiredNames.addAll(checkpointProperties.getExternalApprovalRequiredTools());
        return new RuntimeToolCallbacks(List.copyOf(callbacks), Set.copyOf(approvalRequiredNames));
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
            RuntimeToolCallbacks runtimeToolCallbacks,
            String chatSessionId,
            AgentRuntimeType runtimeType
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        String mergedSystemPrompt = mergeSystemPromptWithLongTermMemory(
                agent.getSystemPrompt(), agent.getId(), chatSessionId);
        mergedSystemPrompt = mergeSystemPromptWithSkillCatalog(mergedSystemPrompt);
        ToolLoopGuard loopGuard = toolLoopGuardFactory.create();
        List<ToolCallback> guardedCallbacks = toolLoopGuardFactory.protect(
                runtimeToolCallbacks.callbacks(),
                loopGuard
        );
        return new ShopAi(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                mergedSystemPrompt,
                chatClient,
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                guardedCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                promptRegistry,
                guardrailsService,
                agentEvalService,
                checkpointService,
                runtimeToolCallbacks.approvalRequiredNames(),
                runtimeType,
                loopGuard
        );
    }

    private String mergeSystemPromptWithLongTermMemory(
            String systemPrompt, String agentId, String chatSessionId) {
        String addon = memoryContextAssembler.buildInjectedContext(agentId, chatSessionId);
        if (!StringUtils.hasText(addon)) {
            return systemPrompt;
        }
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
        List<Message> memory = loadMemory(chatSessionId, null);
        log.info("加载chatSessionId记忆成功");
        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        // 将工具调用转换成 ToolCallback 的形式
        RuntimeToolCallbacks runtimeToolCallbacks = buildToolCallbacks(runtimeTools);

        return buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                runtimeToolCallbacks,
                chatSessionId,
                AgentRuntimeType.SINGLE
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
        List<Message> memory = loadMemory(chatSessionId, "supervisor");

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

        RuntimeToolCallbacks runtimeToolCallbacks = buildToolCallbacks(runtimeTools);

        // 构建 Supervisor 系统提示词
        String supervisorSystemPrompt = buildSupervisorSystemPrompt(agent, agentConfig, knowledgeBases);

        // 获取 ChatClient
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (chatClient == null) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }

        String mergedPrompt = mergeSystemPromptWithLongTermMemory(
                supervisorSystemPrompt, agent.getId(), chatSessionId);
        mergedPrompt = mergeSystemPromptWithSkillCatalog(mergedPrompt);

        int messageLength = agentConfig.getChatOptions() != null && agentConfig.getChatOptions().getMessageLength() != null
                ? agentConfig.getChatOptions().getMessageLength()
                : 10;

        ToolLoopGuard loopGuard = toolLoopGuardFactory.create();
        List<ToolCallback> guardedCallbacks = toolLoopGuardFactory.protect(
                runtimeToolCallbacks.callbacks(),
                loopGuard
        );

        ShopAi shopAi = new ShopAi(
                agent.getId(),
                "Supervisor",
                agent.getDescription(),
                mergedPrompt,
                chatClient,
                messageLength,
                memory,
                guardedCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                promptRegistry,
                guardrailsService,
                agentEvalService,
                checkpointService,
                runtimeToolCallbacks.approvalRequiredNames(),
                AgentRuntimeType.SUPERVISOR,
                loopGuard
        );
        shopAi.setAgentRole("supervisor");
        return shopAi;
    }

    /** 审批执行阶段按当前 Agent 权限重新解析工具，但执行参数来自持久化检查点。 */
    public List<ToolCallback> resolveToolCallbacksForCheckpoint(String agentId) {
        Agent agent = loadAgent(agentId);
        AgentDTO config = toAgentConfig(agent);
        return buildToolCallbacks(resolveRuntimeTools(config)).callbacks();
    }

    private record RuntimeToolCallbacks(
            List<ToolCallback> callbacks,
            Set<String> approvalRequiredNames
    ) {
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
                ? "你拥有的知识库列表:\n" + knowledgeBases.stream()
                    .map(kb -> "- " + kb.getId() + ": " + kb.getName())
                    .collect(java.util.stream.Collectors.joining("\n"))
                : "当前没有可用的知识库。";

        return promptRegistry.render(
                "supervisor.system.v1",
                java.util.Map.of(
                        "specialists", specialistList.toString(),
                        "kbsInfo", kbsInfo
                )
        );
    }
}
