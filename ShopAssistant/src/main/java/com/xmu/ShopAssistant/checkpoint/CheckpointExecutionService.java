package com.xmu.ShopAssistant.checkpoint;

import com.xmu.ShopAssistant.agent.ShopAiFactory;
import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 审批完成后执行持久化的原始 Tool Call，并把结果接回原聊天协议。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointExecutionService {
    private final AgentCheckpointService checkpointService;
    private final ShopAiFactory shopAiFactory;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final SseService sseService;

    /** 返回 true 表示 ToolResponse 已完整持久化，可以继续 Agent Loop。 */
    public boolean processDecision(String checkpointId) {
        AgentCheckpoint current = checkpointService.require(checkpointId);
        if (current.getStatus() == AgentCheckpointStatus.TOOL_COMPLETED
                || current.getStatus() == AgentCheckpointStatus.REJECTION_COMPLETED) {
            return true;
        }
        if (current.getStatus() == AgentCheckpointStatus.REJECTED) {
            AgentCheckpoint rejected = checkpointService.claimRejected(checkpointId).orElse(null);
            if (rejected == null) {
                return false;
            }
            persistRejectedResponses(rejected);
            checkpointService.markRejectedResolved(checkpointId);
            return true;
        }

        AgentCheckpoint checkpoint = checkpointService.claimApproved(checkpointId).orElse(null);
        if (checkpoint == null) {
            log.info("检查点未处于可执行状态，跳过重复消费: checkpointId={}, status={}",
                    checkpointId, current.getStatus());
            return false;
        }

        try {
            Map<String, ToolCallback> callbacks = shopAiFactory
                    .resolveToolCallbacksForCheckpoint(checkpoint.getAgentId())
                    .stream()
                    .collect(Collectors.toMap(
                            callback -> callback.getToolDefinition().name(),
                            callback -> callback,
                            (first, ignored) -> first,
                            LinkedHashMap::new
                    ));

            for (CheckpointToolCall call : checkpoint.getToolCalls()) {
                ToolCallback callback = callbacks.get(call.getName());
                if (callback == null) {
                    throw new IllegalStateException("审批后工具已不可用或权限已被收回: " + call.getName());
                }
                String result = callback.call(call.getArguments());
                persistToolResponse(checkpoint, call, result);
            }
            checkpointService.completeToolExecution(checkpointId);
            return true;
        } catch (Exception e) {
            // 工具可能已产生部分外部副作用，因此失败后不自动重试，交由人工核实。
            checkpointService.fail(checkpointId, e.getMessage());
            log.error("审批后的工具执行失败，不自动重试: checkpointId={}", checkpointId, e);
            return false;
        }
    }

    private void persistRejectedResponses(AgentCheckpoint checkpoint) {
        for (CheckpointToolCall call : checkpoint.getToolCalls()) {
            persistToolResponse(
                    checkpoint,
                    call,
                    "用户拒绝了本次危险操作，工具未执行。请尊重该决定并提供无副作用的替代方案。"
            );
        }
    }

    private void persistToolResponse(AgentCheckpoint checkpoint, CheckpointToolCall call, String result) {
        ToolResponseMessage.ToolResponse response = new ToolResponseMessage.ToolResponse(
                call.getId(),
                call.getName(),
                result == null ? "" : result
        );
        ChatMessageDTO dto = ChatMessageDTO.builder()
                .sessionId(checkpoint.getSessionId())
                .role(ChatMessageDTO.RoleType.TOOL)
                .content(response.responseData())
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(response)
                        .agentRole(checkpoint.getAgentRole())
                        .build())
                .build();
        CreateChatMessageResponse created = chatMessageFacadeService.createChatMessage(dto);
        dto.setId(created.getChatMessageId());
        ChatMessageVO vo = chatMessageConverter.toVO(dto);
        sseService.send(checkpoint.getSessionId(), SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder().message(vo).build())
                .metadata(SseMessage.Metadata.builder().chatMessageId(dto.getId()).build())
                .build());
    }
}
