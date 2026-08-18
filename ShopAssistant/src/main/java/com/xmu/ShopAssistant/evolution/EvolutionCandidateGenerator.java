package com.xmu.ShopAssistant.evolution;

import java.util.List;

public interface EvolutionCandidateGenerator {

    Proposal generate(EvolutionArtifactType type, String targetName, String baseContent,
                      List<EvolutionTrace> traces);

    record Proposal(String content, String rationale) {
    }
}
