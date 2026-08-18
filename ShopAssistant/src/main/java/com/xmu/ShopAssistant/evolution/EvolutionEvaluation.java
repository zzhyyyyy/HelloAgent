package com.xmu.ShopAssistant.evolution;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class EvolutionEvaluation {
    private int sampleCount;
    private double baselineScore;
    private double candidateScore;
    private double improvement;
    private boolean passed;
    private String judgeModel;
    private String summary;
    private Instant evaluatedAt;
}
