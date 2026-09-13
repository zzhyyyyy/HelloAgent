package com.xmu.ShopAssistant.agent;

public enum AgentState {
    IDLE,  // 空闲
    PLANNING,  // 计划中
    THINKING,  // 思考中
    EXECUTING, // 执行中
    WAITING_APPROVAL, // 已创建持久化检查点，等待用户审批
    FINISHED,  // 正常结束
    ERROR  // 错误结束
}
