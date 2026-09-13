package com.xmu.ShopAssistant.agent.loop;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent.tools.loop-guard")
public class ToolLoopGuardProperties {
    private boolean enabled = true;

    /** 同一个工具与等价参数最多允许连续真实执行的次数。 */
    private int maxConsecutiveSameSignature = 3;

    /** 同一个工具连续返回等价结果多少次后熔断。 */
    private int maxConsecutiveSameResult = 3;
}
