package com.xmu.ShopAssistant.checkpoint;

/** 人工审批检查点的持久化状态。 */
public enum AgentCheckpointStatus {
    WAITING_APPROVAL,
    APPROVED,
    EXECUTING,
    REJECTED,
    REJECTION_EXECUTING,
    REJECTION_COMPLETED,
    TOOL_COMPLETED,
    RESUMING,
    COMPLETED,
    FAILED,
    UNKNOWN
}
