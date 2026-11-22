package com.xmu.ShopAssistant.memory;

import java.util.List;

public interface MemoryNoteExtractor {
    List<ExtractedMemoryNote> extract(MemoryEvent event);
}
