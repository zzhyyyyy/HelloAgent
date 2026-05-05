package com.xmu.ShopAssistant.service.impl;

import com.xmu.ShopAssistant.mapper.ChunkBgeM3Mapper;
import com.xmu.ShopAssistant.model.entity.ChunkBgeM3;
import com.xmu.ShopAssistant.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RagServiceImpl implements RagService {
    private static final int DEFAULT_LIMIT = 3;
    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int RRF_K = 60;

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;

    public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.webClient = builder.baseUrl("http://localhost:11434").build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        log.info("EmbeddingSize: {}", resp.getEmbedding().length);
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        List<ChunkBgeM3> chunks = similaritySearchChunks(kbId, title, DEFAULT_LIMIT);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<ChunkBgeM3> similaritySearchChunks(String kbId, String query, int limit) {
        String queryEmbedding = toPgVector(doEmbed(query));
        return chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, limit);
    }

    @Override
    public List<String> hybridSearch(String kbId, String query, int limit) {
        List<ChunkBgeM3> chunks = hybridSearchChunks(kbId, query, limit);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<ChunkBgeM3> hybridSearchChunks(String kbId, String query, int limit) {
        if (!StringUtils.hasText(kbId) || !StringUtils.hasText(query)) {
            return List.of();
        }

        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        int candidateLimit = Math.max(safeLimit, safeLimit * CANDIDATE_MULTIPLIER);

        List<ChunkBgeM3> vectorResults = similaritySearchChunks(kbId, query, candidateLimit);
        List<ChunkBgeM3> bm25Results = chunkBgeM3Mapper.bm25Search(kbId, query, candidateLimit);

        return fuseByRrf(vectorResults, bm25Results, safeLimit);
    }

    private List<ChunkBgeM3> fuseByRrf(List<ChunkBgeM3> vectorResults, List<ChunkBgeM3> bm25Results, int limit) {
        Map<String, ScoreEntry> scoreMap = new LinkedHashMap<>();
        mergeScores(scoreMap, vectorResults);
        mergeScores(scoreMap, bm25Results);

        return scoreMap.values().stream()
                .sorted(Comparator.comparingDouble(ScoreEntry::score).reversed())
                .limit(limit)
                .map(ScoreEntry::chunk)
                .toList();
    }

    private void mergeScores(Map<String, ScoreEntry> scoreMap, List<ChunkBgeM3> rankedResults) {
        if (rankedResults == null || rankedResults.isEmpty()) {
            return;
        }
        for (int i = 0; i < rankedResults.size(); i++) {
            ChunkBgeM3 chunk = rankedResults.get(i);
            if (chunk == null || !StringUtils.hasText(chunk.getId())) {
                continue;
            }
            int rank = i + 1;
            double rrfScore = 1.0 / (RRF_K + rank);

            ScoreEntry existing = scoreMap.get(chunk.getId());
            if (existing == null) {
                scoreMap.put(chunk.getId(), new ScoreEntry(chunk, rrfScore));
                continue;
            }
            existing.addScore(rrfScore);
        }
    }

    private static final class ScoreEntry {
        private final ChunkBgeM3 chunk;
        private double score;

        private ScoreEntry(ChunkBgeM3 chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        private ChunkBgeM3 chunk() {
            return chunk;
        }

        private double score() {
            return score;
        }

        private void addScore(double delta) {
            this.score += delta;
        }
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}
