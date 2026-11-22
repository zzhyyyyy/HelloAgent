package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class MemoryNoteEvidence {
    private String id;
    private String noteId;
    private String eventId;
    private String evidenceRole;
    private String evidenceExcerpt;
    private LocalDateTime createdAt;
}
