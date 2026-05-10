package com.xmu.ShopAssistant.model.request;

import lombok.Data;

@Data
public class CreateChatSessionRequest {
    private String agentId;
    private String title;
    private String mode; // "SINGLE"（默认）或 "MULTI"
}
