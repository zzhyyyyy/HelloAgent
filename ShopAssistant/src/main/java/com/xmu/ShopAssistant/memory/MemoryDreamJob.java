package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryDreamJob {
    private String id;
    private String tenantId;
    private String ownerId;
    private String agentId;
    private String inputSnapshotId;
    private String outputSnapshotId;
    private String instructions;
    private MemoryDreamStatus status;
    private String summary;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
