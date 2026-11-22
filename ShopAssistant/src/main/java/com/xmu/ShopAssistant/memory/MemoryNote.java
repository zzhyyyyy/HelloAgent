package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryNote {
    private String id;
    private String tenantId;
    private String ownerId;
    private String agentId;
    private MemoryScope scope;
    private MemoryNoteType memoryType;
    /** 同一用户范围内用于冲突判断的稳定键。 */
    private String subject;
    private String content;
    private String structuredContent;
    private Double confidence;
    private Double salience;
    private MemoryNoteStatus status;
    private Integer version;
    private String previousNoteId;
    private String source;
    private Integer evidenceCount;
    private LocalDateTime validFrom;
    private LocalDateTime validTo;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
