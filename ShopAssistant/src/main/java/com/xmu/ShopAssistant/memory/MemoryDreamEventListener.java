package com.xmu.ShopAssistant.memory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryDreamEventListener {

    private final MemoryDreamService memoryDreamService;

    @Async
    @EventListener
    public void onRequested(MemoryDreamRequested event) {
        try {
            memoryDreamService.execute(event.jobId());
        } catch (Exception e) {
            log.error("Memory Dreaming 执行失败: jobId={}", event.jobId(), e);
        }
    }
}
