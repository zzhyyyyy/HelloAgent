package com.xmu.ShopAssistant.message;

import com.xmu.ShopAssistant.checkpoint.AgentCheckpointView;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@AllArgsConstructor
@Builder
public class SseMessage {

    private Type type;
    private Payload payload;
    private Metadata metadata;

    @Data
    @AllArgsConstructor
    @Builder
    public static class Payload {
        private ChatMessageVO message;
        private String statusText;
        private Boolean done;
        private AgentCheckpointView checkpoint;
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class Metadata {
        private String chatMessageId;
        private String checkpointId;
    }

    // 自定义消息类型
    // 1. AI 生成(整段消息,持久化完成态)
    // 2. AI 流式 chunk(增量文本,在生成过程中持续推送)
    // 3. AI 规划中
    // 4. AI 思考中
    // 5. AI 执行中
    // 6. AI 完成
    public enum Type {
        AI_GENERATED_CONTENT,
        AI_STREAM_CHUNK,
        AI_EVAL_SCORE,       // assistant 消息持久化后异步产生的 LLM-as-Judge 评分
        AI_APPROVAL_REQUIRED,// 危险工具已暂停，等待用户审批
        AI_APPROVAL_RESOLVED,// 审批已处理或执行失败，移除前端审批卡片
        AI_PLANNING,
        AI_THINKING,
        AI_EXECUTING,
        AI_DONE,
    }
}
