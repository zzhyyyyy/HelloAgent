package com.xmu.ShopAssistant.checkpoint;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 启动恢复：已批准但尚未领取的任务可继续；执行中的任务转为 UNKNOWN，绝不盲目重试。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckpointRecovery {
    private final AgentCheckpointService checkpointService;
    private final ApplicationEventPublisher eventPublisher;

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        for (AgentCheckpoint checkpoint : checkpointService.recoverAfterRestart()) {
            log.info("恢复尚未开始处理的检查点: id={}, status={}", checkpoint.getId(), checkpoint.getStatus());
            eventPublisher.publishEvent(new CheckpointDecisionEvent(checkpoint.getId()));
        }
    }
}
