package com.xmu.ShopAssistant.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 可重复测试的基础整理器。后续 LLM Dream Planner 只需实现同一接口。
 */
@Component
public class DeterministicMemoryDreamPlanner implements MemoryDreamPlanner {

    @Override
    public MemoryDreamPlan plan(List<MemoryEvent> events, List<MemoryNote> notes, String instructions) {
        List<MemoryNote> stable = notes.stream()
                .filter(note -> note.getStatus() == MemoryNoteStatus.ACTIVE)
                .filter(note -> note.getMemoryType() == MemoryNoteType.PROFILE
                        || note.getMemoryType() == MemoryNoteType.PREFERENCE)
                .toList();
        if (stable.isEmpty()) {
            return new MemoryDreamPlan("没有足够的稳定记忆可整理", List.of());
        }

        StringBuilder body = new StringBuilder("# 用户长期记忆\n\n");
        for (MemoryNote note : stable) {
            body.append("- ").append(note.getContent())
                    .append(" <!-- note:").append(note.getId()).append(" -->\n");
        }
        DreamRepresentationDraft profile = new DreamRepresentationDraft(
                MemoryRepresentationType.USER_PROFILE,
                "user-profile",
                "用户长期画像",
                "从已验证的用户画像与偏好笔记整理而成",
                body.toString(),
                stable.stream().map(MemoryNote::getId).toList(),
                "合并稳定画像与偏好，保留原始笔记引用"
        );
        return new MemoryDreamPlan("整理了 " + stable.size() + " 条稳定记忆", List.of(profile));
    }
}
