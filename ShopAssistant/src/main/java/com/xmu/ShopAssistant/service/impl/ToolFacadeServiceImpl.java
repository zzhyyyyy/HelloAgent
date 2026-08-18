package com.xmu.ShopAssistant.service.impl;

import com.xmu.ShopAssistant.agent.tools.Tool;
import com.xmu.ShopAssistant.agent.tools.ToolType;
import com.xmu.ShopAssistant.service.ToolFacadeService;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;

    @Override
    public List<Tool> getAllTools() {
        return tools;
    }

    @Override
    public List<Tool> getOptionalTools() {
        return getToolsByType(ToolType.OPTIONAL);
    }

    @Override
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .toList();
    }
    @PostConstruct  // 在依赖注入完成后执行
    public void init() {
        System.out.println("共注入 " + tools.size() + " 个 Tool:");
        tools.forEach(tool -> {
            System.out.println(" - " + tool.getClass().getSimpleName()
                    + " (类型: " + tool.getType() + ")");
        });
    }
}
