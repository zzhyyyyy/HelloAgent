package com.xmu.ShopAssistant.checkpoint;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * 可跨进程重启恢复的 Agent 业务存档点。
 * 原始 toolCalls 一旦写入便不再由模型重新生成，避免审批对象与实际执行对象不一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCheckpoint {
    private String id;
    private String sessionId;
    private String agentId;
    private String agentRole;
    private AgentRuntimeType runtimeType;
    /** Specialist 暂停时保存原始委派任务，用于审批后恢复其独立上下文。 */
    private String resumeContext;
    private AgentCheckpointStatus status;
    private List<CheckpointToolCall> toolCalls;
    private List<String> approvalRequiredToolNames;
    private Instant createdAt;
    private Instant updatedAt;
    private String errorMessage;
}
