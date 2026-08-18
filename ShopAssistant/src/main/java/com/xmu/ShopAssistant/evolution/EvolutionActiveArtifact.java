package com.xmu.ShopAssistant.evolution;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class EvolutionActiveArtifact {
    private EvolutionArtifactType type;
    private String targetName;
    private String candidateId;
    private String content;
    private Instant activatedAt;
}
