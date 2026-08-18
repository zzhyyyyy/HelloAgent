package com.xmu.ShopAssistant.agent.tools;

import com.xmu.ShopAssistant.agent.tools.security.SensitivityLevel;

public interface Tool {
    String getName();

    String getDescription();

    ToolType getType();

    /**
     * 工具敏感级别,默认 PUBLIC。
     * 危险工具(写库/发邮件/动文件系统)应重写为 DANGEROUS,
     * 由 ToolGuard 配合 ToolSecurityProperties 做限流、审计与策略闸门。
     */
    default SensitivityLevel getSensitivity() {
        return SensitivityLevel.PUBLIC;
    }
}
