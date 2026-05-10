package com.xmu.ShopAssistant.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatSessionDTO {
    private String id;

    private String agentId;

    private String title;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class MetaData {
        private String sessionSummary;
        private Integer summarizedMessageCount;
        private LocalDateTime summaryUpdatedAt;
        private String mode; // "SINGLE" 或 "MULTI"（多Agent模式）
    }
}
