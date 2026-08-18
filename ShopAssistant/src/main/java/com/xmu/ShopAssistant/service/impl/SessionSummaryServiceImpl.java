package com.xmu.ShopAssistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.mapper.AgentMapper;
import com.xmu.ShopAssistant.mapper.ChatMessageMapper;
import com.xmu.ShopAssistant.mapper.ChatSessionMapper;
import com.xmu.ShopAssistant.model.dto.ChatSessionDTO;
import com.xmu.ShopAssistant.model.entity.Agent;
import com.xmu.ShopAssistant.model.entity.ChatMessage;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import com.xmu.ShopAssistant.service.SessionSummaryService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class SessionSummaryServiceImpl implements SessionSummaryService {

    private static final int MIN_MESSAGES_FOR_SUMMARY = 12;
    private static final int MIN_NEW_MESSAGES_FOR_REFRESH = 6;
    private static final int MAX_SNIPPET_LEN = 280;

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentMapper agentMapper;
    private final ChatClientRegistry chatClientRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public void refreshSessionSummary(String agentId, String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            ChatSession session = chatSessionMapper.selectById(sessionId);
            if (session == null) {
                return;
            }

            List<ChatMessage> allMessages = chatMessageMapper.selectBySessionId(sessionId);
            if (allMessages.size() < MIN_MESSAGES_FOR_SUMMARY) {
                return;
            }

            ChatSessionDTO.MetaData metadata = parseMetadata(session.getMetadata());
            int summarizedCount = metadata.getSummarizedMessageCount() == null ? 0 : metadata.getSummarizedMessageCount();
            if (summarizedCount < 0 || summarizedCount > allMessages.size()) {
                summarizedCount = 0;
            }

            int newMessageCount = allMessages.size() - summarizedCount;
            if (newMessageCount < MIN_NEW_MESSAGES_FOR_REFRESH && StringUtils.hasText(metadata.getSessionSummary())) {
                return;
            }

            String incrementalTranscript = buildTranscript(allMessages, summarizedCount);
            if (!StringUtils.hasText(incrementalTranscript)) {
                return;
            }

            String modelKey = resolveModelKey(agentId, session.getAgentId());
            ChatClient chatClient = chatClientRegistry.get(modelKey);
            if (chatClient == null) {
                log.warn("未找到摘要模型 ChatClient: modelKey={}", modelKey);
                return;
            }

            String newSummary = summarize(chatClient, metadata.getSessionSummary(), incrementalTranscript);
            if (!StringUtils.hasText(newSummary)) {
                return;
            }

            ChatSessionDTO.MetaData updated = ChatSessionDTO.MetaData.builder()
                    .mode(metadata.getMode())
                    .sessionSummary(newSummary)
                    .summarizedMessageCount(allMessages.size())
                    .summaryUpdatedAt(LocalDateTime.now())
                    .build();

            ChatSession update = ChatSession.builder()
                    .id(sessionId)
                    .metadata(objectMapper.writeValueAsString(updated))
                    .build();
            chatSessionMapper.updateById(update);
        } catch (Exception e) {
            log.warn("刷新会话摘要失败: sessionId={}", sessionId, e);
        }
    }

    @Override
    public String getSessionSummary(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        ChatSession session = chatSessionMapper.selectById(sessionId);
        if (session == null || !StringUtils.hasText(session.getMetadata())) {
            return null;
        }
        try {
            ChatSessionDTO.MetaData metadata = parseMetadata(session.getMetadata());
            return metadata.getSessionSummary();
        } catch (Exception e) {
            log.warn("读取会话摘要失败: sessionId={}", sessionId, e);
            return null;
        }
    }

    private String resolveModelKey(String requestAgentId, String sessionAgentId) {
        String chosenAgentId = StringUtils.hasText(requestAgentId) ? requestAgentId : sessionAgentId;
        if (StringUtils.hasText(chosenAgentId)) {
            Agent agent = agentMapper.selectById(chosenAgentId);
            if (agent != null && StringUtils.hasText(agent.getModel())) {
                return agent.getModel();
            }
        }
        return "deepseek-chat";
    }

    private ChatSessionDTO.MetaData parseMetadata(String metadataJson) throws Exception {
        if (!StringUtils.hasText(metadataJson)) {
            return ChatSessionDTO.MetaData.builder().build();
        }
        ChatSessionDTO.MetaData metadata = objectMapper.readValue(metadataJson, ChatSessionDTO.MetaData.class);
        return metadata == null ? ChatSessionDTO.MetaData.builder().build() : metadata;
    }

    private String buildTranscript(List<ChatMessage> messages, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message == null || message.getRole() == null || !StringUtils.hasText(message.getContent())) {
                continue;
            }
            String role = message.getRole().toLowerCase();
            if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role)) {
                continue;
            }
            String content = truncate(message.getContent(), MAX_SNIPPET_LEN);
            sb.append("[").append(role).append("] ").append(content).append("\n");
        }
        return sb.toString();
    }

    private String summarize(ChatClient chatClient, String previousSummary, String incrementalTranscript) {
        String systemPrompt = """
                你是会话记忆压缩器。请将“历史摘要 + 新增对话片段”融合成新的滚动摘要。
                要求：
                1) 保留用户稳定偏好、约束条件、关键事实、未完成任务；
                2) 删除寒暄和重复内容；
                3) 输出简洁中文，不超过 220 字；
                4) 仅输出摘要正文，不要输出标题或解释。
                """;
        String userPrompt = """
                历史摘要：
                %s

                新增对话片段：
                %s
                """.formatted(
                StringUtils.hasText(previousSummary) ? previousSummary : "(无)",
                incrementalTranscript
        );

        ChatResponse response = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatClientResponse()
                .chatResponse();

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return null;
        }
        AssistantMessage output = response.getResult().getOutput();
        return truncate(output.getText(), 400);
    }

    private String truncate(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen);
    }
}
