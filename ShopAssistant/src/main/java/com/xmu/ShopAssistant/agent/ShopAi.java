package com.xmu.ShopAssistant.agent;

import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.checkpoint.AgentCheckpointService;
import com.xmu.ShopAssistant.checkpoint.AgentRuntimeType;
import com.xmu.ShopAssistant.agent.loop.ToolLoopGuard;
import com.xmu.ShopAssistant.eval.AgentEvalService;
import com.xmu.ShopAssistant.guardrails.GuardrailsService;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.dto.KnowledgeBaseDTO;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SseService;
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
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class ShopAi {
    private static final String PAPER_RECOMMENDATION =
            "推荐阅读：Vaswani et al., \"Attention Is All You Need\" (NeurIPS 2017), https://arxiv.org/abs/1706.03762";

    private static final List<String> LLM_KEYWORDS = Arrays.asList(
            "llm", "large language model", "transformer", "attention", "rag", "embedding", "fine-tuning",
            "prompt", "inference", "大语言模型", "语言模型", "模型微调", "提示词", "transformers", "自注意力"
    );

    private static final List<String> NO_RECOMMENDATION_KEYWORDS = Arrays.asList(
            "不要推荐", "不需要推荐", "别推荐", "不用推荐", "不想看论文", "不要论文", "不需要论文"
    );

    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    // 默认最多保存 20 条消息
    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    // Prompt 模板注册中心(替代硬编码 prompt)
    private PromptRegistry promptRegistry;

    // Guardrails:输入注入扫描 + 输出 PII/敏感词脱敏
    private GuardrailsService guardrailsService;

    // LLM-as-Judge 自动评估(持久化后异步触发)
    private AgentEvalService agentEvalService;

    // 危险工具命中后创建持久化检查点，不在当前请求线程中阻塞等待。
    private AgentCheckpointService checkpointService;

    private Set<String> approvalRequiredToolNames;

    private AgentRuntimeType runtimeType;

    // 每个 Agent 运行实例独享，避免不同会话之间相互影响计数。
    private ToolLoopGuard toolLoopGuard;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // 多 Agent 模式下标识消息由哪个 Agent 产生
    private String agentRole;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public ShopAi() {
    }

    public ShopAi(String agentId,
                  String name,
                  String description,
                  String systemPrompt,
                  ChatClient chatClient,
                  Integer maxMessages,
                  List<Message> memory,
                  List<ToolCallback> availableTools,
                  List<KnowledgeBaseDTO> availableKbs,
                  String chatSessionId,
                  SseService sseService,
                  ChatMessageFacadeService chatMessageFacadeService,
                  ChatMessageConverter chatMessageConverter,
                  PromptRegistry promptRegistry,
                  GuardrailsService guardrailsService,
                  AgentEvalService agentEvalService,
                  AgentCheckpointService checkpointService,
                  Set<String> approvalRequiredToolNames,
                  AgentRuntimeType runtimeType,
                  ToolLoopGuard toolLoopGuard
    ) {
        this.promptRegistry = promptRegistry;
        this.guardrailsService = guardrailsService;
        this.agentEvalService = agentEvalService;
        this.checkpointService = checkpointService;
        this.approvalRequiredToolNames = approvalRequiredToolNames == null ? Set.of() : Set.copyOf(approvalRequiredToolNames);
        this.runtimeType = runtimeType == null ? AgentRuntimeType.SINGLE : runtimeType;
        this.toolLoopGuard = toolLoopGuard;
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    /** AssistantMessage 落库 + 写入 token 用量。 */
    private void saveMessage(AssistantMessage assistantMessage, ChatMessageDTO.Usage usage) {
        String content = appendPaperRecommendationIfNeeded(assistantMessage.getText());
        ChatMessageDTO.MetaData metaData = ChatMessageDTO.MetaData.builder()
                .toolCalls(assistantMessage.getToolCalls())
                .agentRole(this.agentRole)
                .usage(usage)
                .build();
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .sessionId(this.chatSessionId)
                .metadata(metaData)
                .build();
        CreateChatMessageResponse resp = chatMessageFacadeService.createChatMessage(dto);
        dto.setId(resp.getChatMessageId());
        pendingChatMessages.add(dto);
    }

    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            saveMessage(assistantMessage, null);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO.MetaData toolMetaData = ChatMessageDTO.MetaData.builder()
                        .toolResponse(toolResponse)
                        .agentRole(this.agentRole)
                        .build();
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(toolMetaData)
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    private String appendPaperRecommendationIfNeeded(String assistantText) {
        if (!StringUtils.hasText(assistantText)) {
            return assistantText;
        }
        String lowerAssistant = assistantText.toLowerCase(Locale.ROOT);
        if (lowerAssistant.contains("attention is all you need") || lowerAssistant.contains("1706.03762")) {
            return assistantText;
        }

        String latestUserText = getLatestUserText();
        if (!StringUtils.hasText(latestUserText)) {
            return assistantText;
        }
        if (containsAnyIgnoreCase(latestUserText, NO_RECOMMENDATION_KEYWORDS)) {
            return assistantText;
        }
        if (!containsAnyIgnoreCase(latestUserText, LLM_KEYWORDS)) {
            return assistantText;
        }
        return assistantText + "\n\n" + PAPER_RECOMMENDATION;
    }

    private String getLatestUserText() {
        List<Message> messages = this.chatMemory.get(this.chatSessionId);
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof UserMessage userMessage && StringUtils.hasText(userMessage.getText())) {
                return userMessage.getText();
            }
        }
        return null;
    }

    private boolean containsAnyIgnoreCase(String source, List<String> keywords) {
        if (!StringUtils.hasText(source)) {
            return false;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(k -> lower.contains(k.toLowerCase(Locale.ROOT)));
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    private boolean think() {
        String thinkPrompt = promptRegistry.render(
                "think.system.v1",
                java.util.Map.of("kbs", String.valueOf(this.availableKbs))
        );
        boolean finalAnswerOnly = toolLoopGuard != null && toolLoopGuard.isFused();
        if (finalAnswerOnly) {
            thinkPrompt += "\n\n【工具循环保护】" + toolLoopGuard.getFuseReason()
                    + "。本轮禁止继续调用任何工具，必须基于已有工具结果直接给出最终回答。";
        }

        List<Message> currentMessages = this.chatMemory.get(this.chatSessionId);
        currentMessages = stripOrphanToolResponses(currentMessages);
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(currentMessages)
                .build();

        // —— 流式调用:边收 chunk 边推 SSE
        final String streamId = UUID.randomUUID().toString();
        final StringBuilder accumulated = new StringBuilder();
        final AtomicBoolean didStream = new AtomicBoolean(false);

        var requestSpec = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt);
        if (!finalAnswerOnly) {
            requestSpec = requestSpec.toolCallbacks(this.availableTools.toArray(new ToolCallback[0]));
        }
        List<ChatResponse> chunks = requestSpec
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
                        emitStreamChunk(streamId, accumulated.toString(), false, null, null, null);
                    }
                })
                .collectList()
                .block();

        Assert.notNull(chunks, "Streaming chunks cannot be null");
        if (chunks.isEmpty()) {
            throw new IllegalStateException("LLM 流式响应为空");
        }

        // —— 拼接最终 AssistantMessage(文本 = 累积,工具调用 = 合并所有 chunk)
        ChatResponse last = chunks.get(chunks.size() - 1);
        AssistantMessage lastOut = last.getResult().getOutput();
        List<AssistantMessage.ToolCall> mergedToolCalls = mergeToolCalls(chunks);
        if (finalAnswerOnly) {
            // 即使模型幻觉出工具调用，也不持久化为未配对的协议消息。
            mergedToolCalls = List.of();
        }
        AssistantMessage finalOutput = AssistantMessage.builder()
                .content(accumulated.toString())
                .toolCalls(mergedToolCalls)
                .build();
        // 重建 ChatResponse 供 execute() 取 toolCalls
        this.lastChatResponse = new ChatResponse(
                List.of(new Generation(finalOutput, last.getResult().getMetadata())),
                last.getMetadata()
        );

        // —— 抽 token 用量(usage 一般只在最后一帧带回)
        ChatMessageDTO.Usage usage = extractUsage(last);

        // —— 持久化 + 前端同步
        if (didStream.get()) {
            // 流式文本路径:直接持久化,然后用 done=true 的 chunk 把数据库 id 推过去
            String finalText = appendPaperRecommendationIfNeeded(accumulated.toString());
            String persistedId = persistStreamedAssistant(finalText, mergedToolCalls, usage);
            emitStreamChunk(streamId, finalText, true, persistedId, mergedToolCalls, usage);
            // 异步触发 LLM-as-Judge 评分(不阻塞主流程,失败只记日志)
            if (agentEvalService != null && StringUtils.hasText(finalText)) {
                agentEvalService.evaluateAndPushAsync(persistedId, this.chatSessionId);
            }
        } else {
            // 只有 tool_calls、无文本:走原来的 AI_GENERATED_CONTENT 路径
            saveMessage(finalOutput, usage);
            refreshPendingMessages();
            // 只有 tool_calls 的消息不参与 LLM-as-Judge 评分(无文本可评)
        }

        logToolCalls(finalOutput.getToolCalls());

        return !finalOutput.getToolCalls().isEmpty();
    }

    /** 合并所有 chunk 的 tool_calls,按 id 去重(大多数 provider 在最后一帧才完整给出)。 */
    private List<AssistantMessage.ToolCall> mergeToolCalls(List<ChatResponse> chunks) {
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

    /** 把流式累积的最终 assistant 文本 + tool_calls + usage 落库,返回 DB id。 */
    private String persistStreamedAssistant(String finalContent,
                                            List<AssistantMessage.ToolCall> toolCalls,
                                            ChatMessageDTO.Usage usage) {
        // Guardrails:输出脱敏(PII/敏感词替换为 ***)
        if (guardrailsService != null) {
            finalContent = guardrailsService.scrubOutput(finalContent).getText();
        }
        ChatMessageDTO.MetaData metaData = ChatMessageDTO.MetaData.builder()
                .toolCalls(toolCalls)
                .agentRole(this.agentRole)
                .usage(usage)
                .build();
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(finalContent)
                .sessionId(this.chatSessionId)
                .metadata(metaData)
                .build();
        CreateChatMessageResponse resp = chatMessageFacadeService.createChatMessage(dto);
        return resp.getChatMessageId();
    }

    /**
     * 发送一帧流式 SSE。
     * - 流中(done=false):message.id = streamId,metadata.chatMessageId = null,usage 通常为 null
     * - 流末(done=true):message.id 仍 = streamId(供前端定位 placeholder),
     *                   metadata.chatMessageId = 持久化后的 DB id,usage 一并下发
     */
    private void emitStreamChunk(String streamId, String content, boolean done,
                                 String persistedId,
                                 List<AssistantMessage.ToolCall> toolCalls,
                                 ChatMessageDTO.Usage usage) {
        ChatMessageDTO.MetaData metadata = ChatMessageDTO.MetaData.builder()
                .toolCalls(toolCalls)
                .agentRole(this.agentRole)
                .usage(usage)
                .build();
        ChatMessageVO vo = ChatMessageVO.builder()
                .id(streamId)
                .sessionId(this.chatSessionId)
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .metadata(metadata)
                .build();
        SseMessage sse = SseMessage.builder()
                .type(SseMessage.Type.AI_STREAM_CHUNK)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .done(done)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(persistedId)
                        .build())
                .build();
        sseService.send(this.chatSessionId, sse);
    }

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

    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = this.lastChatResponse.getResult().getOutput().getToolCalls();
        if (checkpointService != null && checkpointService.pauseIfApprovalRequired(
                this.chatSessionId,
                this.agentId,
                this.agentRole,
                this.runtimeType,
                toolCalls,
                this.approvalRequiredToolNames
        ).isPresent()) {
            this.agentState = AgentState.WAITING_APPROVAL;
            log.info("危险工具调用已暂停并保存检查点，sessionId={}, tools={}",
                    this.chatSessionId,
                    toolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    /**
     * 过滤孤立 ToolResponseMessage。
     * DeepSeek API 严格要求每个 tool 角色消息前面必须有带 tool_calls 的 assistant 消息，
     * 否则返回 HTTP 400。该方法移除消息列表中所有不满足此条件的 ToolResponseMessage。
     */
    private List<Message> stripOrphanToolResponses(List<Message> messages) {
        List<Message> result = new ArrayList<>();
        boolean lastWasAssistantWithToolCalls = false;
        for (Message msg : messages) {
            if (msg instanceof ToolResponseMessage) {
                if (!lastWasAssistantWithToolCalls) {
                    log.warn("过滤孤立 ToolResponseMessage，前面无 AssistantMessage[tool_calls]");
                    continue; // 跳过孤立 tool 响应
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

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < MAX_STEPS
                    && agentState != AgentState.FINISHED
                    && agentState != AgentState.WAITING_APPROVAL; i++) {
                // 当前步骤，用于实现 Agent Loop
                int currentStep = i + 1;
                step();
                if (currentStep >= MAX_STEPS && agentState != AgentState.WAITING_APPROVAL) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            if (agentState != AgentState.WAITING_APPROVAL) {
                agentState = AgentState.FINISHED;
            }
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        }
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
