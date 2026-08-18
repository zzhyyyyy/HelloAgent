package com.xmu.ShopAssistant.evolution;

import java.util.List;

public interface EvolutionReplayEvaluator {
    EvolutionEvaluation evaluate(EvolutionCandidate candidate, List<EvolutionTrace> traces);
}
