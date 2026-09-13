package com.xmu.ShopAssistant.checkpoint;

/** 创建检查点时所处的 Agent 运行模式，用于审批后恢复正确的 Harness。 */
public enum AgentRuntimeType {
    SINGLE,
    SUPERVISOR,
    SPECIALIST
}
