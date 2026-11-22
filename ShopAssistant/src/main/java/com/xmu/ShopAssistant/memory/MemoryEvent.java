package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryEvent {
    private String id;
    private String tenantId;
    private String ownerId;
    private String sessionId;
    private String agentId;
    private String agentRole;
    private MemoryEventType eventType;
    private String eventKey;
    private String role;
    private String content;
    /** JSON 字符串，由 MyBatis 写入 JSONB。 */
    private String structuredPayload;
    private MemorySourceType sourceType;
    private MemoryTrustLevel trustLevel;
    private String sourceMessageId;
    private Long sequenceNo;
    private LocalDateTime createdAt;
}
