package com.xmu.ShopAssistant.checkpoint;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Component
@ConfigurationProperties(prefix = "agent.checkpoint")
public class AgentCheckpointProperties {
    /** 是否启用危险工具人工审批。 */
    private boolean enabled = true;

    /** JSON 存档目录；相对路径以应用启动目录为基准。 */
    private String storageDir = "./data/checkpoints";

    /** 外部 MCP ToolCallback 无法读取本地 Tool 敏感级别，需在这里显式声明。 */
    private Set<String> externalApprovalRequiredTools = new LinkedHashSet<>();

    /** 前端参数预览最大长度，完整参数只保存在服务端检查点中。 */
    private int argumentPreviewMaxChars = 500;
}
