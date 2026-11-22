package com.xmu.ShopAssistant.memory;

import java.util.List;

public interface MemoryEvalService {
    MemoryEvalReport evaluate(List<MemoryEvalCase> cases, int retrievalLimit);
}
