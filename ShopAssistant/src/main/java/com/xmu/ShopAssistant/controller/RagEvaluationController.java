package com.xmu.ShopAssistant.controller;

import com.xmu.ShopAssistant.model.common.ApiResponse;
import com.xmu.ShopAssistant.model.request.CosineSimilarityRequest;
import com.xmu.ShopAssistant.model.request.EmbedRequest;
import com.xmu.ShopAssistant.model.request.RagEvaluationRequest;
import com.xmu.ShopAssistant.model.response.CosineSimilarityResponse;
import com.xmu.ShopAssistant.model.response.EmbedResponse;
import com.xmu.ShopAssistant.model.response.RagEvaluationResponse;
import com.xmu.ShopAssistant.service.RagEvaluationService;
import com.xmu.ShopAssistant.service.RagService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@AllArgsConstructor
public class RagEvaluationController {

    private final RagEvaluationService ragEvaluationService;
    private final RagService ragService;

    @PostMapping("/evaluate")
    public ApiResponse<RagEvaluationResponse> evaluate(@RequestBody RagEvaluationRequest request) {
        return ApiResponse.success(ragEvaluationService.evaluate(request));
    }

    @PostMapping("/embed")
    public ApiResponse<EmbedResponse> embed(@RequestBody EmbedRequest request) {
        if (request.getText() == null || request.getText().isBlank()) {
            return ApiResponse.error("text 不能为空");
        }
        int dimensions = request.getDimensions() != null && request.getDimensions() > 0
                ? request.getDimensions() : 1024;
        float[] vector = ragService.embed(request.getText(), dimensions);
        List<Float> embeddingList = new java.util.ArrayList<>(vector.length);
        for (float v : vector) {
            embeddingList.add(v);
        }
        return ApiResponse.success(EmbedResponse.builder()
                .embedding(embeddingList)
                .dimensions(embeddingList.size())
                .build());
    }

    @PostMapping("/cosine-similarity")
    public ApiResponse<CosineSimilarityResponse> cosineSimilarity(@RequestBody CosineSimilarityRequest request) {
        if (request.getText1() == null || request.getText1().isBlank()
                || request.getText2() == null || request.getText2().isBlank()) {
            return ApiResponse.error("text1 和 text2 不能为空");
        }
        int dimensions = request.getDimensions() != null && request.getDimensions() > 0
                ? request.getDimensions() : 1024;
        float[] v1 = ragService.embed(request.getText1(), dimensions);
        float[] v2 = ragService.embed(request.getText2(), dimensions);

        double dot = 0, norm1 = 0, norm2 = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += (double) v1[i] * v2[i];
            norm1 += (double) v1[i] * v1[i];
            norm2 += (double) v2[i] * v2[i];
        }
        double similarity = dot / (Math.sqrt(norm1) * Math.sqrt(norm2));

        return ApiResponse.success(CosineSimilarityResponse.builder()
                .similarity(similarity)
                .dimensions(v1.length)
                .build());
    }
}
