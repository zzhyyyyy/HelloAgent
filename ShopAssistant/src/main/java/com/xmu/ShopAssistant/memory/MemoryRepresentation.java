package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryRepresentation {
    private String id;
    private String tenantId;
    private String ownerId;
    private String agentId;
    private MemoryRepresentationType representationType;
    private String slug;
    private String title;
    private String summary;
    private String body;
    private String links;
    private MemoryRepresentationStatus status;
    private Integer version;
    private String snapshotId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
