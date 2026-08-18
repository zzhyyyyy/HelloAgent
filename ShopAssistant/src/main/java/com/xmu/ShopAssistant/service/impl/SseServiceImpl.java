package com.xmu.ShopAssistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    /** SSE 连接超时:10 分钟(原 30 分钟太长,会积压僵尸连接) */
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;

    /** 心跳间隔:25 秒(EventSource 默认 reconnect ~3s,心跳间隔小于代理常见 idle timeout) */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        // 若已存在旧连接,先优雅关闭再替换,避免双连接
        SseEmitter prev = clients.put(chatSessionId, emitter);
        if (prev != null) {
            try { prev.complete(); } catch (Exception ignored) {}
        }

        try {
            emitter.send(SseEmitter.event().name("init").data("connected"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        emitter.onCompletion(() -> clients.remove(chatSessionId, emitter));
        emitter.onTimeout(() -> {
            log.info("SSE 超时:sessionId={}", chatSessionId);
            clients.remove(chatSessionId, emitter);
        });
        emitter.onError((error) -> {
            log.warn("SSE 错误:sessionId={} err={}", chatSessionId, error.getMessage());
            clients.remove(chatSessionId, emitter);
        });

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);
        if (emitter == null) {
            throw new RuntimeException("No client found for chatSessionId: " + chatSessionId);
        }
        try {
            String body = objectMapper.writeValueAsString(message);
            emitter.send(SseEmitter.event().name("message").data(body));
        } catch (IOException e) {
            log.warn("SSE 发送失败,清理连接 sessionId={}", chatSessionId);
            clients.remove(chatSessionId, emitter);
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
            throw new RuntimeException(e);
        }
    }

    /**
     * 心跳:每 25 秒给所有活跃连接发一个 ping comment(SSE 注释帧,客户端不会触发 onmessage),
     * 用于:
     *  1. 探活——若客户端已断,这次 send 会抛 IOException,我们顺势清理 emitter
     *  2. 防止 nginx/中间代理把空闲连接当超时关掉(常见 idle timeout 30-60s)
     */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        if (clients.isEmpty()) return;
        Iterator<Map.Entry<String, SseEmitter>> it = clients.entrySet().iterator();
        int alive = 0, dropped = 0;
        while (it.hasNext()) {
            Map.Entry<String, SseEmitter> e = it.next();
            SseEmitter emitter = e.getValue();
            try {
                emitter.send(SseEmitter.event().comment("ping"));
                alive++;
            } catch (Exception ex) {
                it.remove();
                dropped++;
                try { emitter.complete(); } catch (Exception ignored) {}
            }
        }
        if (dropped > 0) {
            log.info("SSE heartbeat: alive={} dropped={}", alive, dropped);
        }
    }
}
