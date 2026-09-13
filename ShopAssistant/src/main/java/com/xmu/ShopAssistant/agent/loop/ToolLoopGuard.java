package com.xmu.ShopAssistant.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 单次 Agent 运行级别的工具循环状态机。
 * 参数使用 canonical JSON 比较；结果规范化后只保存 SHA-256，不在内存中重复保存大结果。
 */
@Slf4j
public class ToolLoopGuard {
    private final ObjectMapper objectMapper;
    private final ToolLoopGuardProperties properties;

    private String lastCallSignature;
    private int consecutiveSameSignature;
    private String lastResultSignature;
    private int consecutiveSameResult;
    private boolean fused;
    private String fuseReason;

    public ToolLoopGuard(ObjectMapper objectMapper, ToolLoopGuardProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public synchronized CallDecision beforeCall(String toolName, String arguments) {
        if (!properties.isEnabled()) {
            return CallDecision.allow();
        }
        if (fused) {
            return CallDecision.deny(blockedMessage());
        }

        String signature = toolName + "\n" + canonicalize(arguments);
        if (signature.equals(lastCallSignature)) {
            consecutiveSameSignature++;
        } else {
            lastCallSignature = signature;
            consecutiveSameSignature = 1;
        }

        int maximum = Math.max(1, properties.getMaxConsecutiveSameSignature());
        if (consecutiveSameSignature > maximum) {
            fuse("同一工具与相同参数已连续真实执行 " + maximum + " 次");
            return CallDecision.deny(blockedMessage());
        }
        return CallDecision.allow();
    }

    public synchronized String afterCall(String toolName, String result) {
        if (!properties.isEnabled() || fused) {
            return result;
        }

        String signature = toolName + "\n" + sha256(normalizeResult(result));
        if (signature.equals(lastResultSignature)) {
            consecutiveSameResult++;
        } else {
            lastResultSignature = signature;
            consecutiveSameResult = 1;
        }

        int maximum = Math.max(1, properties.getMaxConsecutiveSameResult());
        if (consecutiveSameResult >= maximum) {
            fuse("工具 " + toolName + " 已连续 " + maximum + " 次返回相同结果");
            return safeResult(result) + "\n\n" + blockedMessage();
        }
        return result;
    }

    public synchronized boolean isFused() {
        return fused;
    }

    public synchronized String getFuseReason() {
        return fuseReason;
    }

    /** JSON 对象递归排序字段；数组保留顺序；非 JSON 统一空白。 */
    String canonicalize(String value) {
        if (value == null) {
            return "null";
        }
        try {
            JsonNode parsed = objectMapper.readTree(value);
            return objectMapper.writeValueAsString(sortJson(parsed));
        } catch (Exception ignored) {
            return normalizeText(value);
        }
    }

    /** 结果是 JSON 时做结构规范化，否则统一换行与空白后再参与摘要。 */
    String normalizeResult(String result) {
        return canonicalize(result == null ? "" : result);
    }

    private JsonNode sortJson(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();
            node.forEach(child -> sorted.add(sortJson(child)));
            return sorted;
        }
        Map<String, JsonNode> fields = new TreeMap<>();
        node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
        ObjectNode sorted = objectMapper.createObjectNode();
        fields.forEach((key, child) -> sorted.set(key, sortJson(child)));
        return sorted;
    }

    private String normalizeText(String value) {
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim()
                .replaceAll("\\s+", " ");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算工具结果摘要失败", e);
        }
    }

    private void fuse(String reason) {
        this.fused = true;
        this.fuseReason = reason;
        log.warn("[ToolLoopGuard] Agent 工具循环已熔断: {}", reason);
    }

    private String blockedMessage() {
        return "[工具循环保护已熔断] " + fuseReason
                + "。本次不会再次执行真实工具，请直接根据已有工具结果生成最终回答。";
    }

    private String safeResult(String result) {
        return result == null ? "" : result;
    }

    public record CallDecision(boolean allowed, String response) {
        static CallDecision allow() {
            return new CallDecision(true, null);
        }

        static CallDecision deny(String response) {
            return new CallDecision(false, response);
        }
    }
}
