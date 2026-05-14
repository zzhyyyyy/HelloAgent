package com.xmu.ShopAssistant.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class DocumentDTO {
    private String id;

    private String kbId;

    private String filename;

    private String filetype;

    private Long size;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    public static class MetaData {
        private String filePath; // 文件存储路径
        private String status;   // PROCESSING / COMPLETED / FAILED
        private Long duration;   // 解析用时（毫秒），仅 COMPLETED 时有值
        private String errorMessage; // 错误信息，仅 FAILED 时有值
    }
}
