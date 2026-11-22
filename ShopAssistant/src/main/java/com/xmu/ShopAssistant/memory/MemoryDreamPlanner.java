package com.xmu.ShopAssistant.memory;

import java.util.List;

public interface MemoryDreamPlanner {
    MemoryDreamPlan plan(List<MemoryEvent> events, List<MemoryNote> notes, String instructions);
}
