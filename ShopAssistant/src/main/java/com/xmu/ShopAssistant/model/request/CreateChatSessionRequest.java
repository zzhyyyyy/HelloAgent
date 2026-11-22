package com.xmu.ShopAssistant.model.request;

import lombok.Data;

@Data
public class CreateChatSessionRequest {
    private String agentId;
    private String title;
    private String mode; // "SINGLE"（默认）或 "MULTI"
    /**
     * 记忆所有者标识。当前单用户调试环境可以不传，由 agent.memory.default-owner-id 兜底；
     * 接入登录体系后应传稳定的用户 ID，禁止使用会话 ID 代替。
     */
    private String memoryOwnerId;
}
