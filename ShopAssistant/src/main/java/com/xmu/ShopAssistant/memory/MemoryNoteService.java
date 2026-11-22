package com.xmu.ShopAssistant.memory;

import java.util.List;

public interface MemoryNoteService {
    void observe(MemoryEvent event);

    List<MemoryNote> activeNotes(String tenantId, String ownerId, String agentId, int limit);
}
