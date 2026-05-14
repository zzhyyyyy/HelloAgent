package com.xmu.ShopAssistant.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentStatusResponse {
    private String status;       // PROCESSING / COMPLETED / FAILED
    private Long duration;       // 解析用时（毫秒），未完成时为 null
    private String errorMessage; // 错误信息，无错误时为 null
}
