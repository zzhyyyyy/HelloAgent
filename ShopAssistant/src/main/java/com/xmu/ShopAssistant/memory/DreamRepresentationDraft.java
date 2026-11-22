package com.xmu.ShopAssistant.memory;

import java.util.List;

public record DreamRepresentationDraft(
        MemoryRepresentationType type,
        String slug,
        String title,
        String summary,
        String body,
        List<String> sourceNoteIds,
        String rationale
) {
}
