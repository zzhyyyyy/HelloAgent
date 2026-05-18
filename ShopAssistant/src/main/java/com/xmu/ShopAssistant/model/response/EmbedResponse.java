package com.xmu.ShopAssistant.model.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EmbedResponse {
    private List<Float> embedding;
    private int dimensions;
}
