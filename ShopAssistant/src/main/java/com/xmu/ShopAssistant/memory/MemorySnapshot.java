package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemorySnapshot {
    private String id;
    private String tenantId;
    private String ownerId;
    private String agentId;
    private Integer version;
    private MemorySnapshotStatus status;
    private String previousSnapshotId;
    private String summary;
    private LocalDateTime createdAt;
    private LocalDateTime activatedAt;
}
