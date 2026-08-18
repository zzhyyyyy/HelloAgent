package com.xmu.ShopAssistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 启动探针:打印当前 Spring 容器里所有 ToolCallbackProvider 和 MCP 相关 bean。
 * 用于诊断 Spring AI MCP autoconfig 是否真的激活、是否产出工具。
 */
@Configuration
@Slf4j
public class McpDiagnostic {

    @Bean
    ApplicationRunner mcpStartupProbe(
            ApplicationContext ctx,
            @Autowired(required = false) List<ToolCallbackProvider> providers
    ) {
        return args -> {
            log.info("===== [MCP-Probe] 启动诊断 =====");
            log.info("[MCP-Probe] ToolCallbackProvider 总数: {}", providers == null ? 0 : providers.size());
            if (providers != null) {
                for (ToolCallbackProvider p : providers) {
                    ToolCallback[] cbs;
                    try {
                        cbs = p.getToolCallbacks();
                    } catch (Exception e) {
                        log.warn("[MCP-Probe]   provider={} 取 callbacks 抛错: {}",
                                p.getClass().getName(), e.getMessage());
                        continue;
                    }
                    log.info("[MCP-Probe]   provider={} callbacks={}",
                            p.getClass().getName(), cbs == null ? 0 : cbs.length);
                    if (cbs != null) {
                        for (ToolCallback cb : cbs) {
                            log.info("[MCP-Probe]       tool={}", cb.getToolDefinition().name());
                        }
                    }
                }
            }
            // 列容器里所有名字含 mcp / Mcp 的 bean
            Map<String, Object> mcpBeans = ctx.getBeansOfType(Object.class);
            long mcpCount = mcpBeans.keySet().stream()
                    .filter(n -> n.toLowerCase().contains("mcp"))
                    .peek(n -> log.info("[MCP-Probe]   bean: {}", n))
                    .count();
            log.info("[MCP-Probe] 名字含 mcp 的 bean 总数: {}", mcpCount);
            log.info("===== [MCP-Probe] 结束 =====");
        };
    }
}
