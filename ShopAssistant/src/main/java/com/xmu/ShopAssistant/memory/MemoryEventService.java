package com.xmu.ShopAssistant.memory;

import com.xmu.ShopAssistant.model.entity.ChatMessage;

import java.util.List;

public interface MemoryEventService {
    void captureChatMessage(String requestAgentId, ChatMessage chatMessage);

    List<MemoryEvent> findByOwner(String tenantId, String ownerId, int limit);

    List<MemoryEvent> findBySession(String tenantId, String ownerId, String sessionId, int limit);
}
