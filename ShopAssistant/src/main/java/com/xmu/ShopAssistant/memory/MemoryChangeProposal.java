package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryChangeProposal {
    private String id;
    private String dreamJobId;
    private String snapshotId;
    private String changeType;
    private String targetType;
    private String targetId;
    private String rationale;
    private String evidence;
    private MemoryProposalStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime decidedAt;
}
