package com.xmu.ShopAssistant.memory;

public record ExtractedMemoryNote(
        MemoryScope scope,
        MemoryNoteType memoryType,
        String subject,
        String content,
        String structuredContent,
        double confidence,
        double salience,
        String source
) {
}
