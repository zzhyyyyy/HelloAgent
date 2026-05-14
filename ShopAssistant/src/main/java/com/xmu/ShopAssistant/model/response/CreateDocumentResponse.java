package com.xmu.ShopAssistant.model.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateDocumentResponse {
    private String documentId;
    private long duration;     // 文档解析用时（毫秒），同步模式有效
    private boolean processing; // true 表示文件正在异步解析中
}

