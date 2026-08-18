package com.xmu.ShopAssistant.evolution;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class EvolutionCandidate {
    private String id;
    private EvolutionArtifactType type;
    private String targetName;
    private String baseVersion;
    private String candidateVersion;
    private String content;
    private String rationale;
    private List<String> sourceTraceIds;
    private EvolutionCandidateStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private EvolutionEvaluation evaluation;
    private String previousContent;
}
