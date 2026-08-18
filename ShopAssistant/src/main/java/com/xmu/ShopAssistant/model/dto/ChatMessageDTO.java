package com.xmu.ShopAssistant.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ChatMessageDTO {
    private String id;

    private String sessionId;

    private RoleType role;

    private String content;

    private MetaData metadata;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    @Builder
    public static class MetaData {
        private ToolResponseMessage.ToolResponse toolResponse;
        private List<AssistantMessage.ToolCall> toolCalls;
        private String agentRole;  // 多Agent模式下标识消息由哪个Agent产生: "supervisor", "specialist:{name}"
        private Usage usage;       // LLM token 计量(仅 assistant 消息)
        private EvalScore evalScore; // LLM-as-Judge 自动评分(仅 assistant 消息,异步写入)
    }

    /**
     * 单条 assistant 消息的 LLM-as-Judge 自动评分。
     * 由 AgentEvalService 在持久化后异步调用 judge 模型产出,落到 metadata.evalScore。
     */
    @Data
    @Builder
    public static class EvalScore {
        private Integer correctness;   // 正确性 0-5
        private Integer completeness;  // 完整性 0-5
        private Integer relevance;     // 相关性 0-5
        private String reasoning;      // 评分理由(供前端 hover 展示)
        private String judgeModel;     // 评分模型名
    }

    /**
     * Token 用量(从 Spring AI ChatResponse.getMetadata().getUsage() 抽取)。
     * 单条 assistant 消息的成本切片;会话/Agent 维度的聚合在调用方累加。
     */
    @Data
    @Builder
    public static class Usage {
        private Long promptTokens;
        private Long completionTokens;
        private Long totalTokens;
        private String model;        // 实际产生本条回复的模型(供后续路由/计费用)
        private String finishReason; // 可选:stop / length / tool_calls 等
    }

    @Getter
    @AllArgsConstructor
    public enum RoleType {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system"),
        TOOL("tool");

        @JsonValue
        private final String role;

        public static RoleType fromRole(String role) {
            for (RoleType value : values()) {
                if (value.role.equals(role)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Invalid role: " + role);
        }
    }
}
