package com.xmu.ShopAssistant.guardrails;

import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 输入/输出守护:
 *  - checkInput:扫描 prompt injection 关键词,命中则拒绝
 *  - scrubOutput:扫描 PII / 敏感词,命中替换为 ***
 *
 * 命中行为统一写入审计日志 [Guardrail],可被日志聚合工具消费。
 */
@Slf4j
@Service
@EnableConfigurationProperties(GuardrailsProperties.class)
public class GuardrailsService {

    private static final String MASK = "***";

    private final GuardrailsProperties props;
    private final List<Pattern> injection;
    private final List<Pattern> pii;
    private final List<Pattern> sensitive;

    public GuardrailsService(GuardrailsProperties props) {
        this.props = props;
        this.injection = compile(props.getInjectionPatterns());
        this.pii = compile(props.getPiiPatterns());
        this.sensitive = compile(props.getSensitiveKeywords());
    }

    private static List<Pattern> compile(List<String> raw) {
        if (raw == null) return List.of();
        List<Pattern> out = new ArrayList<>();
        for (String s : raw) {
            try {
                out.add(Pattern.compile(s));
            } catch (Exception e) {
                log.warn("Guardrails 正则编译失败 pattern={} err={}", s, e.getMessage());
            }
        }
        return out;
    }

    public InputCheck checkInput(String userText) {
        if (!props.isEnabled() || userText == null || userText.isBlank()) {
            return InputCheck.builder().allowed(true).build();
        }
        for (Pattern p : injection) {
            if (p.matcher(userText).find()) {
                String reason = "命中 prompt injection 模式: " + p.pattern();
                audit("input_blocked", reason, summarize(userText));
                return InputCheck.builder()
                        .allowed(false)
                        .reason(reason)
                        .build();
            }
        }
        return InputCheck.builder().allowed(true).build();
    }

    /** 输出脱敏:替换 PII/敏感词,统计命中次数。 */
    public OutputScrub scrubOutput(String assistantText) {
        if (!props.isEnabled() || assistantText == null || assistantText.isBlank()) {
            return OutputScrub.builder().text(assistantText).hits(0).build();
        }
        String out = assistantText;
        int hits = 0;
        for (Pattern p : pii) {
            String prev = out;
            out = p.matcher(out).replaceAll(MASK);
            if (!prev.equals(out)) {
                hits++;
                audit("pii_scrubbed", p.pattern(), null);
            }
        }
        for (Pattern p : sensitive) {
            String prev = out;
            out = p.matcher(out).replaceAll(MASK);
            if (!prev.equals(out)) {
                hits++;
                audit("sensitive_scrubbed", p.pattern(), null);
            }
        }
        return OutputScrub.builder().text(out).hits(hits).build();
    }

    private void audit(String kind, String detail, String sample) {
        if (!props.isAuditEnabled()) return;
        log.info("[Guardrail] kind={} detail={} sample={}", kind, detail, sample);
    }

    private static String summarize(String s) {
        if (s == null) return null;
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }

    @Data
    @Builder
    public static class InputCheck {
        private boolean allowed;
        private String reason;
    }

    @Data
    @Builder
    public static class OutputScrub {
        private String text;
        private int hits;
    }
}
