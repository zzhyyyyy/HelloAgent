package com.xmu.ShopAssistant.memory;

/**
 * 事件来源可信等级。该字段用于后续记忆晋升策略，不代表内容一定正确。
 */
public enum MemoryTrustLevel {
    SYSTEM_TRUSTED,
    USER_ASSERTED,
    MODEL_GENERATED,
    EXTERNAL_UNTRUSTED
}
