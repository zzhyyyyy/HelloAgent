package com.xmu.ShopAssistant.memory;

public enum MemoryEventType {
    USER_MESSAGE,
    ASSISTANT_MESSAGE,
    SYSTEM_MESSAGE,
    TOOL_CALL,
    TOOL_RESULT,
    APPROVAL_REQUEST,
    APPROVAL_DECISION,
    EVAL_RESULT,
    TASK_COMPLETED,
    TASK_FAILED,
    USER_CORRECTION
}
