package com.xmu.ShopAssistant.agent.tools;

import com.xmu.ShopAssistant.skill.Skill;
import com.xmu.ShopAssistant.skill.SkillRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 渐进式披露的第 2 层入口:模型在 system prompt 看到 Skill 目录(name+description),
 * 决定相关时调用 useSkill(name) 把完整 SKILL.md 正文取回,再按正文行动。
 *
 * 始终注册为 FIXED 工具,这样每个 Agent 默认都能用 Skill。
 */
@Component
@Slf4j
public class UseSkillTool implements Tool {

    private final SkillRegistry skillRegistry;

    public UseSkillTool(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    @Override
    public String getName() {
        return "useSkill";
    }

    @Override
    public String getDescription() {
        return "加载某个 Skill 的完整操作指令(渐进式披露第 2 层)。在系统提示词中可以看到 Skill 目录(name + 简介),当用户的任务与某 Skill 描述匹配时调用本工具获取完整流程。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "useSkill",
            description = "加载某个 Skill 的完整操作指令。参数:skillName(skill 的 name)。" +
                    "返回这个 Skill 的完整流程文本,你应按返回内容按步骤行动并调用相应工具。" +
                    "若 skill 不存在则返回错误说明。"
    )
    public String useSkill(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return "错误:skillName 不能为空。";
        }
        Skill skill = skillRegistry.get(skillName.trim());
        if (skill == null) {
            return "错误:未找到名为 " + skillName + " 的 Skill。当前可用 Skill 见 system prompt 中的目录。";
        }
        log.info("[Skill] 加载: name={}", skill.getName());
        StringBuilder sb = new StringBuilder();
        sb.append("【Skill: ").append(skill.getName()).append("】\n");
        if (skill.getAllowedTools() != null && !skill.getAllowedTools().isEmpty()) {
            sb.append("(本 Skill 推荐使用工具: ")
                    .append(String.join(", ", skill.getAllowedTools()))
                    .append(")\n\n");
        }
        sb.append(skill.getInstructions());
        sb.append("\n\n[提示] 以上是 Skill 的完整流程,请按步骤行动并在必要时调用相应工具,不要重复调用 useSkill 加载同一个 Skill。");
        return sb.toString();
    }
}
