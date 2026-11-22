package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MemoryEvalReport {
    private int totalCases;
    private int passedCases;
    private double casePassRate;
    private double factRecall;
    private double leakageRate;
    private List<MemoryEvalResult> results;
}
