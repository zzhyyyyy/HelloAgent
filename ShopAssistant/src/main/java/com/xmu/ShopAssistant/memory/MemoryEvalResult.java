package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MemoryEvalResult {
    private String caseId;
    private boolean passed;
    private int expectedCount;
    private int matchedCount;
    private List<String> missingFacts;
    private List<String> leakedFacts;
    private List<String> retrievedSourceIds;
}
