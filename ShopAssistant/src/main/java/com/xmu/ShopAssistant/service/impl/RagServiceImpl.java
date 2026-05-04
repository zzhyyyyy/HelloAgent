package com.xmu.ShopAssistant.service.impl;

import com.xmu.ShopAssistant.mapper.ChunkBgeM3Mapper;
import com.xmu.ShopAssistant.model.entity.ChunkBgeM3;
import com.xmu.ShopAssistant.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class RagServiceImpl implements RagService {

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
        List<ChunkBgeM3> chunks = similaritySearchChunks(kbId, title, 3);
        return chunks.stream().map(ChunkBgeM3::getContent).toList();
    }

    @Override
    public List<ChunkBgeM3> similaritySearchChunks(String kbId, String query, int limit) {
        String queryEmbedding = toPgVector(doEmbed(query));
        return chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, limit);
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
