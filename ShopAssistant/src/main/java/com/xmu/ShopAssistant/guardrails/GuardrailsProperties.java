package com.xmu.ShopAssistant.guardrails;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@Data
@ConfigurationProperties(prefix = "agent.guardrails")
public class GuardrailsProperties {

    private boolean enabled = true;
    private boolean auditEnabled = true;

    /** 输入注入关键词(命中即拒,以防 prompt injection / jailbreak)。 */
    private List<String> injectionPatterns = List.of(
            "(?i)ignore previous instructions",
            "(?i)ignore the above",
            "(?i)disregard prior",
            "(?i)you are now",
            "(?i)forget your instructions",
            "(?i)忽略之前的(指令|提示)",
            "(?i)你现在不是",
            "(?i)act as (?:if|though|a )"
    );

    /** 输出脱敏正则(命中替换为 ***)。 */
    private List<String> piiPatterns = List.of(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",         // email
            "\\b1[3-9]\\d{9}\\b",                                              // CN phone
            "\\b\\d{17}[\\dXx]\\b",                                            // CN ID 18-digit
            "\\b(?:\\d[ -]?){13,19}\\b"                                        // credit card 13-19 digit
    );

    /** 输出敏感词(命中替换为 ***;可加业务关键词如机密项目代号)。 */
    private List<String> sensitiveKeywords = List.of();
}
