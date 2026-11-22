package com.xmu.ShopAssistant.memory;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemorySnapshotItem {
    private String snapshotId;
    private String itemType;
    private String itemId;
}
