package com.xmu.ShopAssistant.checkpoint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/** 返回给前端的检查点视图，不暴露完整的内部持久化结构。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCheckpointView {
    private String id;
    private String sessionId;
    private AgentCheckpointStatus status;
    private List<String> toolNames;
    private String argumentPreview;
    private Instant createdAt;
    private String errorMessage;
}
