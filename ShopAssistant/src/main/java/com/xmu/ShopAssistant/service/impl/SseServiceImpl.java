package com.xmu.ShopAssistant.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.message.SseMessage;
import com.xmu.ShopAssistant.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 会话级 SSE 连接与短期事件重放。
 *
 * 每个会话维护一个有界事件缓冲。浏览器 EventSource 重连时会自动携带 Last-Event-ID，
 * 服务端先重放该事件之后的内容，再放开实时推送，避免网络短暂中断造成的流式消息缺失。
 */
@Slf4j
@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    /** 单条 SSE 连接最长存活 10 分钟。 */
    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;
    /** 心跳间隔，小于常见反向代理的空闲超时。 */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000L;
    /** 每个会话最多保留最近 200 条事件，防止长流无限占用内存。 */
    private static final int MAX_REPLAY_EVENTS = 200;
    /** 已断开会话的重放窗口和空闲通道保留时间。 */
    private static final Duration REPLAY_RETENTION = Duration.ofMinutes(10);

    private final ConcurrentMap<String, SessionChannel> channels = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId, String lastEventId) {
        SessionChannel channel = channels.computeIfAbsent(chatSessionId, ignored -> new SessionChannel());
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        emitter.onCompletion(() -> detachEmitter(channel, emitter));
        emitter.onTimeout(() -> {
            log.info("SSE 超时: sessionId={}", chatSessionId);
            detachEmitter(channel, emitter);
        });
        emitter.onError(error -> {
            log.warn("SSE 错误: sessionId={} err={}", chatSessionId, error.getMessage());
            detachEmitter(channel, emitter);
        });

        SseEmitter previous;
        synchronized (channel.monitor) {
            channel.touch();
            previous = channel.emitter;
            channel.emitter = emitter;

            try {
                ResumePlan plan = channel.planResume(lastEventId);
                emitter.send(SseEmitter.event().name("init").data(toJson(new InitEvent("connected"))));
                for (ReplayEvent replayEvent : plan.events()) {
                    sendReplayEvent(emitter, replayEvent);
                }
                if (plan.requested()) {
                    emitter.send(SseEmitter.event()
                            .name("resumed")
                            .data(toJson(new ResumeEvent(plan.events().size(), plan.complete()))));
                }
            } catch (IOException | RuntimeException e) {
                if (channel.emitter == emitter) {
                    channel.emitter = null;
                }
                emitter.completeWithError(e);
                throw new IllegalStateException("建立 SSE 连接失败", e);
            }
        }

        if (previous != null && previous != emitter) {
            try {
                previous.complete();
            } catch (Exception ignored) {
                // 旧连接已断开时无需额外处理。
            }
        }
        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SessionChannel channel = channels.computeIfAbsent(chatSessionId, ignored -> new SessionChannel());
        String body = toJson(message);

        synchronized (channel.monitor) {
            channel.touch();
            ReplayEvent event = channel.append(body);
            SseEmitter emitter = channel.emitter;
            if (emitter == null) {
                log.debug("SSE 客户端暂未连接，事件已缓存: sessionId={} eventId={}", chatSessionId, event.id());
                return;
            }

            try {
                sendReplayEvent(emitter, event);
            } catch (IOException e) {
                log.warn("SSE 发送失败，保留事件等待重连: sessionId={} eventId={}", chatSessionId, event.id());
                if (channel.emitter == emitter) {
                    channel.emitter = null;
                }
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                    // 连接已关闭。
                }
            }
        }
    }

    private void sendReplayEvent(SseEmitter emitter, ReplayEvent event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.id())
                .name("message")
                .data(event.body()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 SSE 事件失败", e);
        }
    }

    private void detachEmitter(SessionChannel channel, SseEmitter emitter) {
        synchronized (channel.monitor) {
            if (channel.emitter == emitter) {
                channel.emitter = null;
                channel.touch();
            }
        }
    }

    /** 心跳探活，并回收失去连接且已超过重放窗口的会话通道。 */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, SessionChannel> entry : channels.entrySet()) {
            String sessionId = entry.getKey();
            SessionChannel channel = entry.getValue();
            SseEmitter emitterToClose = null;
            boolean removeChannel;

            synchronized (channel.monitor) {
                channel.trimExpiredEvents(now);
                SseEmitter emitter = channel.emitter;
                if (emitter != null) {
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                        channel.touch();
                    } catch (Exception e) {
                        channel.emitter = null;
                        emitterToClose = emitter;
                        channel.touch();
                        log.info("SSE 心跳发现断开连接: sessionId={}", sessionId);
                    }
                }
                removeChannel = channel.emitter == null
                        && channel.events.isEmpty()
                        && now - channel.lastTouchedAt >= REPLAY_RETENTION.toMillis();
            }

            if (emitterToClose != null) {
                try {
                    emitterToClose.complete();
                } catch (Exception ignored) {
                    // 已关闭。
                }
            }
            if (removeChannel) {
                channels.remove(sessionId, channel);
            }
        }
    }

    private static final class SessionChannel {
        private final Object monitor = new Object();
        private final String generationId = UUID.randomUUID().toString();
        private final Deque<ReplayEvent> events = new ArrayDeque<>();
        private long nextSequence = 0L;
        private long lastTouchedAt = System.currentTimeMillis();
        private SseEmitter emitter;

        private void touch() {
            lastTouchedAt = System.currentTimeMillis();
        }

        private ReplayEvent append(String body) {
            ReplayEvent event = new ReplayEvent(
                    generationId + ":" + (++nextSequence),
                    body,
                    System.currentTimeMillis()
            );
            events.addLast(event);
            trimExpiredEvents(System.currentTimeMillis());
            return event;
        }

        private ResumePlan planResume(String lastEventId) {
            if (!StringUtils.hasText(lastEventId)) {
                return new ResumePlan(false, true, List.of());
            }

            Long lastSequence = parseSequence(lastEventId);
            if (lastSequence == null) {
                return new ResumePlan(true, false, List.of());
            }

            List<ReplayEvent> missing = new ArrayList<>();
            for (ReplayEvent event : events) {
                Long eventSequence = parseSequence(event.id());
                if (eventSequence != null && eventSequence > lastSequence) {
                    missing.add(event);
                }
            }

            boolean complete = true;
            ReplayEvent earliest = events.peekFirst();
            if (earliest == null) {
                // 已产生过的事件全部过期，且客户端尚未收到最新序号时无法补齐。
                complete = lastSequence >= nextSequence;
            } else {
                Long earliestSequence = parseSequence(earliest.id());
                complete = earliestSequence == null || lastSequence >= earliestSequence - 1;
            }
            return new ResumePlan(true, complete, missing);
        }

        private Long parseSequence(String eventId) {
            String prefix = generationId + ":";
            if (!StringUtils.hasText(eventId) || !eventId.startsWith(prefix)) {
                return null;
            }
            try {
                return Long.parseLong(eventId.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        private void trimExpiredEvents(long now) {
            while (!events.isEmpty()) {
                ReplayEvent first = events.peekFirst();
                boolean overCapacity = events.size() > MAX_REPLAY_EVENTS;
                boolean expired = first != null && now - first.createdAt() >= REPLAY_RETENTION.toMillis();
                if (!overCapacity && !expired) {
                    return;
                }
                events.removeFirst();
            }
        }
    }

    private record ReplayEvent(String id, String body, long createdAt) {
    }

    private record ResumePlan(boolean requested, boolean complete, List<ReplayEvent> events) {
    }

    private record InitEvent(String status) {
    }

    private record ResumeEvent(int replayedEventCount, boolean replayComplete) {
    }
}
