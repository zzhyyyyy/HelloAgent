package com.xmu.ShopAssistant.memory;

import java.util.List;

public interface MemoryRetrievalService {
    List<MemorySearchResult> search(
            String tenantId, String ownerId, String agentId, String query, int limit);

    MemoryRepresentation readPage(String tenantId, String ownerId, String pageId);
}
