package com.xmu.ShopAssistant.service.impl;

import com.xmu.ShopAssistant.model.entity.ChunkBgeM3;
import com.xmu.ShopAssistant.model.request.RagEvaluationRequest;
import com.xmu.ShopAssistant.model.response.RagEvaluationResponse;
import com.xmu.ShopAssistant.service.RagEvaluationService;
import com.xmu.ShopAssistant.service.RagService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@AllArgsConstructor
public class RagEvaluationServiceImpl implements RagEvaluationService {

    private static final int DEFAULT_TOP_K = 3;

    private final RagService ragService;

    @Override
    public RagEvaluationResponse evaluate(RagEvaluationRequest request) {
        if (request == null) {
            return RagEvaluationResponse.builder()
                    .topK(DEFAULT_TOP_K)
                    .totalCases(0)
                    .metrics(RagEvaluationResponse.Metrics.builder()
                            .hitRateAtK(0.0)
                            .avgRecallAtK(0.0)
                            .mrrAtK(0.0)
                            .ndcgAtK(0.0)
                            .build())
                    .caseResults(List.of())
                    .build();
        }
        int topK = resolveTopK(request.getTopK());
        List<RagEvaluationRequest.EvalCase> evalCases = request.getCases() == null ? List.of() : request.getCases();
        List<RagEvaluationResponse.CaseResult> caseResults = new ArrayList<>();

        double sumHit = 0.0;
        double sumRecall = 0.0;
        double sumMrr = 0.0;
        double sumNdcg = 0.0;
        int validCases = 0;

        for (RagEvaluationRequest.EvalCase evalCase : evalCases) {
            if (!isValidCase(evalCase)) {
                continue;
            }
            Set<String> relevantSet = new LinkedHashSet<>(evalCase.getRelevantDocIds());
            // 多路召回检索 稠密向量Embedding + 稀疏向量BM25
            List<ChunkBgeM3> retrievedChunks = ragService.hybridSearchChunks(evalCase.getKbId(), evalCase.getQuery(), topK);
            List<String> retrievedDocIds = toOrderedUniqueDocIds(retrievedChunks);

            double hit = hitAtK(retrievedDocIds, relevantSet);
            double recall = recallAtK(retrievedDocIds, relevantSet);
            double rr = reciprocalRank(retrievedDocIds, relevantSet);
            double ndcg = ndcgAtK(retrievedDocIds, relevantSet, topK);

            sumHit += hit;
            sumRecall += recall;
            sumMrr += rr;
            sumNdcg += ndcg;
            validCases++;

            caseResults.add(RagEvaluationResponse.CaseResult.builder()
                    .kbId(evalCase.getKbId())
                    .query(evalCase.getQuery())
                    .retrievedDocIds(retrievedDocIds)
                    .relevantDocIds(new ArrayList<>(relevantSet))
                    .hitAtK(round4(hit))
                    .recallAtK(round4(recall))
                    .reciprocalRank(round4(rr))
                    .ndcgAtK(round4(ndcg))
                    .build());
        }

        double denom = validCases == 0 ? 1.0 : validCases;
        RagEvaluationResponse.Metrics metrics = RagEvaluationResponse.Metrics.builder()
                .hitRateAtK(round4(sumHit / denom))
                .avgRecallAtK(round4(sumRecall / denom))
                .mrrAtK(round4(sumMrr / denom))
                .ndcgAtK(round4(sumNdcg / denom))
                .build();

        return RagEvaluationResponse.builder()
                .topK(topK)
                .totalCases(validCases)
                .metrics(metrics)
                .caseResults(caseResults)
                .build();
    }

    private int resolveTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, 20);
    }

    private boolean isValidCase(RagEvaluationRequest.EvalCase evalCase) {
        return evalCase != null
                && StringUtils.hasText(evalCase.getKbId())
                && StringUtils.hasText(evalCase.getQuery())
                && evalCase.getRelevantDocIds() != null
                && !evalCase.getRelevantDocIds().isEmpty();
    }

    private List<String> toOrderedUniqueDocIds(List<ChunkBgeM3> chunks) {
        Set<String> docIds = new LinkedHashSet<>();
        if (chunks == null) {
            return List.of();
        }
        for (ChunkBgeM3 chunk : chunks) {
            if (chunk != null && StringUtils.hasText(chunk.getDocId())) {
                docIds.add(chunk.getDocId());
            }
        }
        return new ArrayList<>(docIds);
    }

    private double hitAtK(List<String> retrievedDocIds, Set<String> relevantSet) {
        for (String docId : retrievedDocIds) {
            if (relevantSet.contains(docId)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    private double recallAtK(List<String> retrievedDocIds, Set<String> relevantSet) {
        if (relevantSet.isEmpty()) {
            return 0.0;
        }
        int hitCount = 0;
        for (String docId : retrievedDocIds) {
            if (relevantSet.contains(docId)) {
                hitCount++;
            }
        }
        return (double) hitCount / relevantSet.size();
    }

    private double reciprocalRank(List<String> retrievedDocIds, Set<String> relevantSet) {
        for (int i = 0; i < retrievedDocIds.size(); i++) {
            if (relevantSet.contains(retrievedDocIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double ndcgAtK(List<String> retrievedDocIds, Set<String> relevantSet, int topK) {
        double dcg = 0.0;
        for (int i = 0; i < retrievedDocIds.size() && i < topK; i++) {
            int rel = relevantSet.contains(retrievedDocIds.get(i)) ? 1 : 0;
            if (rel > 0) {
                dcg += 1.0 / log2(i + 2);
            }
        }
        int idealHits = Math.min(topK, relevantSet.size());
        double idcg = 0.0;
        for (int i = 0; i < idealHits; i++) {
            idcg += 1.0 / log2(i + 2);
        }
        if (idcg == 0.0) {
            return 0.0;
        }
        return dcg / idcg;
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2);
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
