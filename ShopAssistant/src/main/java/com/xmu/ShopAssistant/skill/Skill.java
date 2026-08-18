package com.xmu.ShopAssistant.skill;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一个 Skill = 流程方法论(指令) + 元数据 + 它需要的工具列表。
 *
 * 与 Tool 的区别:
 * - Tool 是单个函数(模型调用即执行一个动作)
 * - Skill 是一段告诉模型「遇到这类任务该按什么步骤、用哪些工具去做」的程序性知识
 *
 * 与 Specialist Agent 的区别:
 * - Specialist 拥有独立上下文/工具集/system prompt,本质是个独立 Agent
 * - Skill 由主 Agent 自己加载并按 SKILL.md 正文行动,不开新 context
 */
@Data
@Builder
public class Skill {
    /** 唯一名称(目录名),如 order-refund */
    private String name;
    /** 一句话描述,用于渐进式披露的第 1 层(永远在 system prompt 里) */
    private String description;
    /** SKILL.md 正文,第 2 层内容,模型调 useSkill 后才返回 */
    private String instructions;
    /** 该 skill 声明需要的工具名(对应 Tool.getName());可空 */
    private List<String> allowedTools;
    /** Skill 资源目录绝对路径,供后续拓展(模板、附件等) */
    private String resourceDir;
}
