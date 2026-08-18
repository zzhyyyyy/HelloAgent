package com.xmu.ShopAssistant.agent.tools.security;

/**
 * 工具敏感级别。用于在工具层做权限/限流/审计的统一抉择。
 *
 * - PUBLIC     无外部副作用、无成本,所有 Agent 可用(知识检索、终止)
 * - RESTRICTED 有外部成本/速率敏感(联网搜索)
 * - DANGEROUS  可写入外部状态或读取本机资源(数据库、邮件、文件系统)
 */
public enum SensitivityLevel {
    PUBLIC,
    RESTRICTED,
    DANGEROUS
}
