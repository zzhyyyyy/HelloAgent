package com.xmu.ShopAssistant.skill;

import com.xmu.ShopAssistant.agent.tools.UseSkillTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsCatalogAndDisclosesInstructionsOnDemand() throws Exception {
        Path skillDir = Files.createDirectories(tempDir.resolve("order-refund"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: order-refund
                description: 处理退款请求
                allowedTools: [dataBaseTool, emailTool]
                ---

                # 退款流程
                先核实订单，再发送通知。
                """, StandardCharsets.UTF_8);

        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.load();

        assertEquals(1, registry.all().size());
        assertEquals("- order-refund: 处理退款请求", registry.catalogForPrompt());
        assertFalse(registry.catalogForPrompt().contains("先核实订单"));

        String disclosed = new UseSkillTool(registry).useSkill("order-refund");
        assertTrue(disclosed.contains("# 退款流程"));
        assertTrue(disclosed.contains("dataBaseTool, emailTool"));
    }

    @Test
    void rejectsUnknownSkillWithoutLeakingInstructions() {
        SkillRegistry registry = new SkillRegistry(tempDir.toString());
        registry.load();

        String result = new UseSkillTool(registry).useSkill("missing");

        assertTrue(result.startsWith("错误:未找到"));
    }
}
