package com.xmu.ShopAssistant.evolution;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "agent.evolution")
public class EvolutionProperties {

    /** 是否采集用于受控演化的执行轨迹。 */
    private boolean enabled = true;

    /** 轨迹与候选版本的本地持久化根目录。 */
    private String storageDir = "./data/evolution";

    /** 三项 Judge 平均分不高于该值时标记为低分样本。 */
    private double lowScoreThreshold = 3.0;

    /** 查询接口单次允许返回的最大轨迹数量。 */
    private int maxQueryLimit = 200;

    /** 当前 Agent 主循环使用的 Prompt 版本，随轨迹一同记录。 */
    private String promptVersion = "think.system.v1";

    /** 生成候选 Prompt/Skill 使用的模型。 */
    private String candidateModel = "deepseek-chat";

    /** 单个候选版本最多吸收的低分轨迹数，避免上下文无限膨胀。 */
    private int maxCandidateTraces = 20;
}
