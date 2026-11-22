package com.xmu.ShopAssistant.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryEvalCase {
    private String id;
    private String tenantId;
    private String ownerId;
    private String agentId;
    private String query;
    private List<String> expectedFacts = new ArrayList<>();
    private List<String> forbiddenFacts = new ArrayList<>();
}
