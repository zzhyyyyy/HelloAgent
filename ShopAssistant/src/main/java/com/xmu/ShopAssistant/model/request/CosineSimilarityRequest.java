package com.xmu.ShopAssistant.model.request;

import lombok.Data;

@Data
public class CosineSimilarityRequest {
    private String text1;
    private String text2;
    private Integer dimensions;
}
