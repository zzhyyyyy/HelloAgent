package com.xmu.ShopAssistant.checkpoint;

import com.xmu.ShopAssistant.agent.ShopAi;
import com.xmu.ShopAssistant.agent.ShopAiFactory;
import com.xmu.ShopAssistant.mapper.ChatSessionMapper;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SpecialistRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** API 请求只负责记录用户决定；实际工具执行和 Agent 恢复放到异步线程。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckpointDecisionListener {
    private final AgentCheckpointService checkpointService;
    private final CheckpointExecutionService executionService;
    private final ShopAiFactory shopAiFactory;
    private final SpecialistRunner specialistRunner;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageFacadeService chatMessageFacadeService;

    @Async
    @EventListener
    public void onDecision(CheckpointDecisionEvent event) {
        if (!executionService.processDecision(event.checkpointId())) {
            return;
        }
        AgentCheckpoint checkpoint = checkpointService.claimContinuation(event.checkpointId()).orElse(null);
        if (checkpoint == null) {
            log.info("检查点恢复阶段已被其他消费者领取: {}", event.checkpointId());
            return;
        }

        try {
            if (checkpoint.getRuntimeType() == AgentRuntimeType.SPECIALIST) {
                resumeSpecialistAndSupervisor(checkpoint);
                checkpointService.completeContinuation(event.checkpointId());
                return;
            }
            ShopAi agent = checkpoint.getRuntimeType() == AgentRuntimeType.SUPERVISOR
                    ? shopAiFactory.createSupervisor(checkpoint.getAgentId(), checkpoint.getSessionId())
                    : shopAiFactory.create(checkpoint.getAgentId(), checkpoint.getSessionId());
            agent.run();
            checkpointService.completeContinuation(event.checkpointId());
        } catch (Exception e) {
            checkpointService.fail(
                    event.checkpointId(),
                    "审批决定已经处理，但恢复 Agent Loop 失败。危险工具可能已经执行，请勿直接重试；原因: " + e.getMessage()
            );
            log.error("检查点处理完成，但恢复 Agent Loop 失败: checkpointId={}", event.checkpointId(), e);
        }
    }

    private void resumeSpecialistAndSupervisor(AgentCheckpoint checkpoint) {
        String result = specialistRunner.resume(
                checkpoint.getAgentId(),
                checkpoint.getResumeContext(),
                checkpoint.getSessionId(),
                checkpoint.getAgentRole()
        );
        if (SpecialistRunner.waitingApprovalResult(checkpoint.getAgentId()).equals(result)) {
            return;
        }

        // Supervisor 的历史恢复会过滤 Specialist 协议消息，通过一条内部系统消息接收最终结果。
        chatMessageFacadeService.createChatMessage(ChatMessageDTO.builder()
                .sessionId(checkpoint.getSessionId())
                .role(ChatMessageDTO.RoleType.SYSTEM)
                .content("【Specialist 审批后执行结果】\n" + result)
                .build());

        ChatSession session = chatSessionMapper.selectById(checkpoint.getSessionId());
        if (session == null) {
            throw new IllegalStateException("恢复 Supervisor 失败，会话不存在: " + checkpoint.getSessionId());
        }
        ShopAi supervisor = shopAiFactory.createSupervisor(session.getAgentId(), checkpoint.getSessionId());
        supervisor.run();
    }
}
