package com.xmu.ShopAssistant.prompt;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集中管理所有 prompt 模板。
 *
 * 约定:
 * - 模板放在 classpath:prompts/*.st
 * - 文件名即模板名(含版本号),如 think.system.v1.st → name="think.system.v1"
 * - 升级新版本:加 v2 文件并改调用端,旧版可暂留作回滚
 *
 * 用法:
 *   String rendered = promptRegistry.render("think.system.v1", Map.of("kbs", knowledgeBases));
 */
@Slf4j
@Component
public class PromptRegistry {

    private final Map<String, String> templates = new HashMap<>();
    private final Map<String, String> activeOverrides = new ConcurrentHashMap<>();

    @PostConstruct
    void load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:prompts/*.st");
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename == null) continue;
                String name = filename.endsWith(".st")
                        ? filename.substring(0, filename.length() - 3)
                        : filename;
                String content = StreamUtils.copyToString(r.getInputStream(), StandardCharsets.UTF_8);
                templates.put(name, content);
                log.info("Prompt 已加载: name={} chars={}", name, content.length());
            }
            log.info("PromptRegistry 就绪,共 {} 个模板", templates.size());
        } catch (IOException e) {
            log.error("加载 prompt 模板失败", e);
        }
    }

    /** 渲染模板。变量用 {var} 占位。 */
    public String render(String name, Map<String, Object> vars) {
        String template = require(name);
        return new PromptTemplate(template).render(vars == null ? Map.of() : vars);
    }

    /** 取原始模板内容(未渲染)。 */
    public String get(String name) {
        return require(name);
    }

    /** 受控演化发布入口：只覆盖内存中的活动版本，classpath 基线始终保留。 */
    public void activate(String name, String content) {
        if (!templates.containsKey(name)) {
            throw new IllegalArgumentException("未找到 prompt 模板: " + name);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Prompt 内容不能为空");
        }
        activeOverrides.put(name, content);
        log.info("Prompt 活动版本已切换: name={} chars={}", name, content.length());
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    private String require(String name) {
        String t = activeOverrides.getOrDefault(name, templates.get(name));
        if (t == null) {
            throw new IllegalArgumentException("未找到 prompt 模板: " + name);
        }
        return t;
    }
}
