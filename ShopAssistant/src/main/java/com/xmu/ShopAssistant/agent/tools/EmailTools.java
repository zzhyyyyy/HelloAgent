package com.xmu.ShopAssistant.agent.tools;

import com.xmu.ShopAssistant.agent.tools.security.GuardOutcome;
import com.xmu.ShopAssistant.agent.tools.security.SensitivityLevel;
import com.xmu.ShopAssistant.agent.tools.security.ToolGuard;
import com.xmu.ShopAssistant.agent.tools.security.ToolSecurityProperties;
import com.xmu.ShopAssistant.service.EmailService;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class EmailTools implements Tool {

    private final EmailService emailService;
    private final ToolGuard guard;
    private final ToolSecurityProperties props;

    public EmailTools(EmailService emailService, ToolGuard guard, ToolSecurityProperties props) {
        this.emailService = emailService;
        this.guard = guard;
        this.props = props;
    }

    @Override
    public String getName() {
        return "emailTool";
    }

    @Override
    public String getDescription() {
        return "受沙箱保护的邮件发送工具:收件人需在 allowlist 内,主题/正文长度受限,带速率与每日上限。";
    }

    @Override
    public ToolType getType() {
        return ToolType.OPTIONAL;
    }

    @Override
    public SensitivityLevel getSensitivity() {
        return SensitivityLevel.DANGEROUS;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "sendEmail",
            description = "发送邮件。参数:to(收件人,需在 allowlist 内)、subject(主题)、content(正文)。" +
                    "异步发送。受速率限制(默认 3 封/分钟,20 封/天)、长度限制、收件人白名单约束。"
    )
    public String sendEmail(String to, String subject, String content) {
        // —— 1. 基本非空
        if (to == null || to.isBlank()) return "错误:收件人不能为空。";
        if (subject == null || subject.isBlank()) return "错误:主题不能为空。";
        if (content == null || content.isBlank()) return "错误:正文不能为空。";

        String toTrim = to.trim();

        // —— 2. RFC 5322 邮箱校验
        try {
            InternetAddress addr = new InternetAddress(toTrim, true);
            addr.validate();
        } catch (AddressException e) {
            return "错误:邮箱格式不合法。";
        }

        // —— 3. 长度限制
        ToolSecurityProperties.Email cfg = props.getEmail();
        if (subject.length() > cfg.getMaxSubjectLength()) {
            return "错误:主题长度超过 " + cfg.getMaxSubjectLength() + " 字符。";
        }
        if (content.length() > cfg.getMaxBodyLength()) {
            return "错误:正文长度超过 " + cfg.getMaxBodyLength() + " 字符。";
        }

        // —— 4. Header injection 防护(主题严禁包含 CR/LF)
        if (subject.indexOf('\n') >= 0 || subject.indexOf('\r') >= 0) {
            guard.audit(getName(), "sendEmail",
                    Map.of("to", toTrim, "reason", "header_injection"),
                    "DENIED", "");
            return "错误:主题包含非法换行字符。";
        }

        // —— 5. 收件人白名单(默认空 = 全拒)
        List<String> allowlist = cfg.getRecipientAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            guard.audit(getName(), "sendEmail",
                    Map.of("to", toTrim, "reason", "no_allowlist"),
                    "DENIED", "");
            return "错误:未在 agent.tools.security.email.recipient-allowlist 中配置允许的收件人,邮件未发送。";
        }
        if (!isAllowed(toTrim, allowlist)) {
            guard.audit(getName(), "sendEmail",
                    Map.of("to", toTrim, "reason", "not_in_allowlist"),
                    "DENIED", "");
            return "错误:收件人不在白名单内。";
        }

        // —— 6. 速率限制 + 每日上限
        GuardOutcome outcome = guard.check(getName(), "sendEmail");
        if (!outcome.allowed()) {
            return outcome.message();
        }

        // —— 7. 异步发送
        emailService.sendEmailAsync(toTrim, subject.trim(), content.trim());
        guard.audit(getName(), "sendEmail",
                Map.of("to", toTrim, "subject", subject.trim()),
                "OK", "");
        log.info("邮件已提交异步发送, to={}, subject={}", toTrim, subject.trim());

        return "邮件已提交异步发送。收件人: " + toTrim + ",主题: " + subject.trim();
    }

    /** glob 风格匹配:支持 user@domain.com / *@domain.com / *@*.com */
    private boolean isAllowed(String email, List<String> patterns) {
        String lower = email.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            String regex = "^" + Pattern.quote(p.trim().toLowerCase(Locale.ROOT))
                    .replace("*", "\\E.*\\Q") + "$";
            if (lower.matches(regex)) {
                return true;
            }
        }
        return false;
    }
}
