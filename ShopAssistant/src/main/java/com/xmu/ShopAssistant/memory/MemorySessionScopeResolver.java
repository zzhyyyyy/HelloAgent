package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.mapper.ChatSessionMapper;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemorySessionScopeResolver {

    private final ChatSessionMapper chatSessionMapper;
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    public MemorySessionScope resolve(String requestAgentId, String sessionId) {
        ChatSession session = StringUtils.hasText(sessionId) ? chatSessionMapper.selectById(sessionId) : null;
        String ownerId = properties.getDefaultOwnerId();
        String agentId = requestAgentId;
        if (session != null) {
            if (!StringUtils.hasText(agentId)) agentId = session.getAgentId();
            if (StringUtils.hasText(session.getMetadata())) {
                try {
                    JsonNode metadata = objectMapper.readTree(session.getMetadata());
                    String configuredOwner = metadata.path("memoryOwnerId").asText(null);
                    if (StringUtils.hasText(configuredOwner)) ownerId = configuredOwner;
                } catch (Exception e) {
                    log.warn("解析会话记忆所有者失败，使用默认所有者: sessionId={}", sessionId);
                }
            }
        }
        return new MemorySessionScope(properties.getDefaultTenantId(), ownerId, agentId);
    }
}
