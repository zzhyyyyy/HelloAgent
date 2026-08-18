package com.xmu.ShopAssistant.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描 ./data/skills/<name>/SKILL.md,解析 YAML frontmatter + markdown body,缓存为 Skill。
 * 渐进式披露由调用方决定:
 *  - 第 1 层:只读 name + description(便宜地塞入 system prompt)
 *  - 第 2 层:模型调 useSkill(name) 时返回 instructions(SKILL.md 正文)
 */
@Slf4j
@Component
public class SkillRegistry {

    /** 解析最朴素的 YAML frontmatter:开头 `---` 与下一行 `---` 之间。 */
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*\\R", Pattern.DOTALL);

    private final Map<String, Skill> skills = new LinkedHashMap<>();
    private final Path skillsDir;

    public SkillRegistry(@Value("${agent.skills.dir:./data/skills}") String skillsDirCfg) {
        this.skillsDir = Paths.get(skillsDirCfg).toAbsolutePath().normalize();
    }

    @PostConstruct
    void load() {
        try {
            if (!Files.exists(skillsDir)) {
                Files.createDirectories(skillsDir);
                log.info("Skills 目录不存在,已创建: {}", skillsDir);
                return;
            }
            try (Stream<Path> stream = Files.list(skillsDir)) {
                stream.filter(Files::isDirectory).forEach(this::tryLoadOne);
            }
            log.info("SkillRegistry 就绪,共加载 {} 个 skill: {}", skills.size(), skills.keySet());
        } catch (IOException e) {
            log.warn("加载 skills 失败 dir={} err={}", skillsDir, e.getMessage());
        }
    }

    private void tryLoadOne(Path dir) {
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            log.warn("Skill 目录缺少 SKILL.md: {}", dir);
            return;
        }
        try {
            String raw = Files.readString(md, StandardCharsets.UTF_8);
            Skill skill = parse(raw, dir);
            if (skill != null) {
                skills.put(skill.getName(), skill);
                log.info("已加载 Skill: name={} desc={} tools={}",
                        skill.getName(), skill.getDescription(), skill.getAllowedTools());
            }
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败 path={} err={}", md, e.getMessage());
        }
    }

    /** 极简 frontmatter 解析:name/description 必填,allowedTools 可选(YAML 数组 `[a, b]` 或省略)。 */
    Skill parse(String raw, Path dir) {
        Matcher m = FRONTMATTER.matcher(raw);
        if (!m.find()) {
            log.warn("SKILL.md 缺少 frontmatter: {}", dir);
            return null;
        }
        Map<String, String> meta = parseSimpleYaml(m.group(1));
        String body = raw.substring(m.end()).trim();

        String name = meta.getOrDefault("name", dir.getFileName().toString()).trim();
        String description = meta.getOrDefault("description", "").trim();
        List<String> allowedTools = parseInlineList(meta.get("allowedTools"));

        return Skill.builder()
                .name(name)
                .description(description)
                .instructions(body)
                .allowedTools(allowedTools)
                .resourceDir(dir.toString())
                .build();
    }

    /** 仅支持 `key: value` 行,够 SKILL.md 元数据用,不引入 SnakeYAML。 */
    private Map<String, String> parseSimpleYaml(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String val = line.substring(colon + 1).trim();
            // 剥两侧引号
            if ((val.startsWith("\"") && val.endsWith("\"")) ||
                    (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            map.put(key, val);
        }
        return map;
    }

    /** 解析 `[a, b, c]` 风格的内联数组。 */
    private List<String> parseInlineList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String trimmed = raw.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) return List.of();
        return Arrays.stream(trimmed.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public Collection<Skill> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    public Skill get(String name) {
        return skills.get(name);
    }

    /** 渐进式披露 · 第 1 层:目录(name + description),供 system prompt 使用。 */
    public String catalogForPrompt() {
        if (skills.isEmpty()) return "(当前无可用 Skill)";
        List<String> lines = new ArrayList<>();
        for (Skill s : skills.values()) {
            lines.add("- " + s.getName() + ": " + s.getDescription());
        }
        return String.join("\n", lines);
    }
}
