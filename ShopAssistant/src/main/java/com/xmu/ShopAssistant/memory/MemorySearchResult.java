package com.xmu.ShopAssistant.memory;

public record MemorySearchResult(
        String sourceType,
        String sourceId,
        String title,
        String content,
        double score
) {
}
