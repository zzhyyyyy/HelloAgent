package com.xmu.ShopAssistant.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CosineSimilarityResponse {
    private double similarity;
    private int dimensions;
}
