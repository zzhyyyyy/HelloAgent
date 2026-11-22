package com.xmu.ShopAssistant.memory;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent.memory")
public class MemoryProperties {

    /** 是否采集统一记忆事件。 */
    private boolean enabled = true;

    /** 记忆旁路故障时是否允许聊天主链路继续。 */
    private boolean failOpen = true;

    /** 当前未接入租户体系时的默认租户。 */
    private String defaultTenantId = "local";

    /** 当前未接入登录体系时的默认用户。 */
    private String defaultOwnerId = "local-user";

    private InjectedContext injectedContext = new InjectedContext();

    @Data
    public static class InjectedContext {
        /** 记忆注入总预算，使用轻量估算器计算，不等同于模型供应商精确计费 Token。 */
        private int totalTokenBudget = 1800;
        /** 每轮必加载的稳定画像预算。 */
        private int stableProfileBudget = 350;
        /** 根据当前问题检索的笔记和知识页预算。 */
        private int retrievedMemoryBudget = 1000;
        private int maxNotes = 8;
        private int maxPages = 3;
    }
}
