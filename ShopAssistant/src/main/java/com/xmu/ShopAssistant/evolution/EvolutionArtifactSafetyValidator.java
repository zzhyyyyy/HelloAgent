package com.xmu.ShopAssistant.evolution;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class EvolutionArtifactSafetyValidator {

    private static final int MAX_CONTENT_LENGTH = 100_000;
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)sk-[a-z0-9]{10,}"),
            Pattern.compile("(?i)(api[_-]?key|password|secret)\\s*[:=]\\s*[^${\\s][^\\s]{5,}"),
            Pattern.compile("(?i)(绕过|忽略).{0,12}(审批|权限|安全|守护)"),
            Pattern.compile("(?i)bypass.{0,12}(approval|permission|security|guardrail)")
    );

    public void validate(EvolutionCandidate candidate) {
        String content = candidate.getContent();
        if (content == null || content.isBlank()) throw new IllegalArgumentException("候选内容不能为空");
        if (content.length() > MAX_CONTENT_LENGTH) throw new IllegalArgumentException("候选内容超过安全长度限制");
        for (Pattern pattern : FORBIDDEN) {
            if (pattern.matcher(content).find()) {
                throw new IllegalArgumentException("候选内容未通过安全检查");
            }
        }
        if (candidate.getType() == EvolutionArtifactType.SKILL && !content.stripLeading().startsWith("---")) {
            throw new IllegalArgumentException("Skill 候选必须包含 YAML frontmatter");
        }
    }
}
