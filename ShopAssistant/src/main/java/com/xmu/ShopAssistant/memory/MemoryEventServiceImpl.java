package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.model.entity.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryEventServiceImpl implements MemoryEventService {

    private static final int DEFAULT_QUERY_LIMIT = 200;
    private static final int MAX_QUERY_LIMIT = 1000;

    private final MemoryEventMapper memoryEventMapper;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;
    private final MemoryNoteService memoryNoteService;
    private final MemorySessionScopeResolver scopeResolver;

    @Override
    public void captureChatMessage(String requestAgentId, ChatMessage chatMessage) {
        if (!properties.isEnabled() || chatMessage == null || !StringUtils.hasText(chatMessage.getSessionId())) {
            return;
        }
        try {
            MemorySessionScope scope = scopeResolver.resolve(requestAgentId, chatMessage.getSessionId());
            JsonNode metadata = parseJson(chatMessage.getMetadata());
            MemoryEventType eventType = eventTypeOf(chatMessage.getRole());
            MemoryEvent messageEvent = baseEvent(scope, chatMessage, metadata, eventType, "message",
                    trustLevelOf(eventType), metadata);
            if (append(messageEvent) && eventType == MemoryEventType.USER_MESSAGE) {
                memoryNoteService.observe(messageEvent);
            }

            if (eventType == MemoryEventType.ASSISTANT_MESSAGE && metadata.path("toolCalls").isArray()) {
                int index = 0;
                for (JsonNode toolCall : metadata.path("toolCalls")) {
                    String callId = firstText(toolCall, "id", "name");
                    append(baseEvent(scope, chatMessage, metadata, MemoryEventType.TOOL_CALL,
                            "tool-call:" + (StringUtils.hasText(callId) ? callId : index),
                            MemoryTrustLevel.MODEL_GENERATED, toolCall));
                    index++;
                }
            }
        } catch (RuntimeException e) {
            if (!properties.isFailOpen()) {
                throw e;
            }
            log.warn("记忆事件采集失败，聊天主链路继续: sessionId={} messageId={} err={}",
                    chatMessage.getSessionId(), chatMessage.getId(), e.getMessage());
        }
    }

    @Override
    public List<MemoryEvent> findByOwner(String tenantId, String ownerId, int limit) {
        requireScope(tenantId, ownerId);
        return memoryEventMapper.selectByOwner(tenantId, ownerId, safeLimit(limit));
    }

    @Override
    public List<MemoryEvent> findBySession(String tenantId, String ownerId, String sessionId, int limit) {
        requireScope(tenantId, ownerId);
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return memoryEventMapper.selectBySession(tenantId, ownerId, sessionId, safeLimit(limit));
    }

    private MemoryEvent baseEvent(
            MemorySessionScope scope,
            ChatMessage message,
            JsonNode metadata,
            MemoryEventType eventType,
            String eventKey,
            MemoryTrustLevel trustLevel,
            JsonNode payload
    ) {
        return MemoryEvent.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(scope.tenantId())
                .ownerId(scope.ownerId())
                .sessionId(message.getSessionId())
                .agentId(scope.agentId())
                .agentRole(textOrNull(metadata.path("agentRole")))
                .eventType(eventType)
                .eventKey(eventKey)
                .role(message.getRole())
                .content(message.getContent())
                .structuredPayload(toJson(payload))
                .sourceType(MemorySourceType.CHAT_MESSAGE)
                .trustLevel(trustLevel)
                .sourceMessageId(message.getId())
                .createdAt(message.getCreatedAt() == null ? LocalDateTime.now() : message.getCreatedAt())
                .build();
    }

    private boolean append(MemoryEvent event) {
        return memoryEventMapper.insert(event) > 0;
    }

    private JsonNode parseJson(String value) {
        if (!StringUtils.hasText(value)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            return objectMapper.createObjectNode().put("rawMetadata", value);
        }
    }

    private String toJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value == null ? objectMapper.createObjectNode() : value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化记忆事件载荷失败", e);
        }
    }

    private MemoryEventType eventTypeOf(String role) {
        if ("user".equalsIgnoreCase(role)) return MemoryEventType.USER_MESSAGE;
        if ("assistant".equalsIgnoreCase(role)) return MemoryEventType.ASSISTANT_MESSAGE;
        if ("tool".equalsIgnoreCase(role)) return MemoryEventType.TOOL_RESULT;
        return MemoryEventType.SYSTEM_MESSAGE;
    }

    private MemoryTrustLevel trustLevelOf(MemoryEventType eventType) {
        return switch (eventType) {
            case USER_MESSAGE, USER_CORRECTION -> MemoryTrustLevel.USER_ASSERTED;
            case ASSISTANT_MESSAGE, TOOL_CALL -> MemoryTrustLevel.MODEL_GENERATED;
            case TOOL_RESULT -> MemoryTrustLevel.EXTERNAL_UNTRUSTED;
            default -> MemoryTrustLevel.SYSTEM_TRUSTED;
        };
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = textOrNull(node.path(name));
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText(null);
    }

    private int safeLimit(int limit) {
        if (limit <= 0) return DEFAULT_QUERY_LIMIT;
        return Math.min(limit, MAX_QUERY_LIMIT);
    }

    private void requireScope(String tenantId, String ownerId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("tenantId 和 ownerId 不能为空");
        }
    }

}
