package com.xmu.ShopAssistant.agent.tools.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用的统一闸口:速率限制 + 每日上限 + 结构化审计。
 *
 * 设计简单,不引入新依赖(token bucket 自实现)。
 * 后续可平滑迁移到 Resilience4j RateLimiter / Bulkhead。
 */
@Slf4j
@Component
public class ToolGuard {

    private final ToolSecurityProperties props;
    private final ConcurrentMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DailyCounter> dailyCounters = new ConcurrentHashMap<>();

    public ToolGuard(ToolSecurityProperties props) {
        this.props = props;
    }

    /** 调用前校验:速率限制 + 每日上限(只对 email 启用)。 */
    public GuardOutcome check(String toolName, String operation) {
        TokenBucket bucket = buckets.computeIfAbsent(toolName, this::buildBucket);
        if (!bucket.tryAcquire()) {
            return GuardOutcome.deny(
                    "工具 " + toolName + " 触发速率限制,请稍后再试。请优先基于已有信息作答,而不是立即重试。"
            );
        }
        if ("emailTool".equals(toolName)) {
            DailyCounter counter = dailyCounters.computeIfAbsent(toolName,
                    k -> new DailyCounter(props.getEmail().getDailyCap()));
            if (!counter.tryIncrement()) {
                return GuardOutcome.deny(
                        "当日发送邮件已达上限 (" + props.getEmail().getDailyCap() + " 封),今日不再发送。"
                );
            }
        }
        return GuardOutcome.allow();
    }

    /** 结构化审计日志,带工具/操作/结果/上下文。 */
    public void audit(String toolName, String operation, Map<String, Object> args,
                      String outcome, String detail) {
        if (!props.isAuditEnabled()) return;
        log.info("[ToolAudit] tool={} op={} outcome={} detail={} args={}",
                toolName, operation, outcome, detail == null ? "" : detail, args);
    }

    private TokenBucket buildBucket(String toolName) {
        int perMinute = switch (toolName) {
            case "dataBaseTool" -> props.getDatabase().getPermitsPerMinute();
            case "emailTool" -> props.getEmail().getPermitsPerMinute();
            case "fileSystemTool" -> props.getFilesystem().getPermitsPerMinute();
            default -> 600; // 其它工具默认 10 QPS
        };
        int capacity = Math.max(1, perMinute);
        double refillPerSec = perMinute / 60.0;
        return new TokenBucket(capacity, refillPerSec);
    }

    // ---------- 内部工具 ----------

    private static final class TokenBucket {
        private final double capacity;
        private final double refillPerSec;
        private double tokens;
        private long lastRefillMs;

        TokenBucket(double capacity, double refillPerSec) {
            this.capacity = capacity;
            this.refillPerSec = refillPerSec;
            this.tokens = capacity;
            this.lastRefillMs = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            double elapsedSec = (now - lastRefillMs) / 1000.0;
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSec);
            lastRefillMs = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private static final class DailyCounter {
        private final int cap;
        private final AtomicInteger count = new AtomicInteger();
        private volatile long resetAtMs;

        DailyCounter(int cap) {
            this.cap = cap;
            this.resetAtMs = nextMidnight();
        }

        boolean tryIncrement() {
            long now = System.currentTimeMillis();
            if (now >= resetAtMs) {
                synchronized (this) {
                    if (now >= resetAtMs) {
                        count.set(0);
                        resetAtMs = nextMidnight();
                    }
                }
            }
            return count.incrementAndGet() <= cap;
        }

        private static long nextMidnight() {
            return LocalDate.now()
                    .plusDays(1)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        }
    }
}
