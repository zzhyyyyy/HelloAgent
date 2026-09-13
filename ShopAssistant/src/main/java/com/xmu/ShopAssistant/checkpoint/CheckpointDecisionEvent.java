package com.xmu.ShopAssistant.checkpoint;

/** 审批状态写入持久化存储后发布，监听器再异步执行或拒绝原始 Tool Call。 */
public record CheckpointDecisionEvent(String checkpointId) {
}
