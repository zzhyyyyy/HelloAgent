package com.xmu.ShopAssistant.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ToolLoopGuardFactory {
    private final ObjectMapper objectMapper;
    private final ToolLoopGuardProperties properties;

    public ToolLoopGuard create() {
        return new ToolLoopGuard(objectMapper, properties);
    }

    public List<ToolCallback> protect(List<ToolCallback> callbacks, ToolLoopGuard guard) {
        return callbacks.stream()
                .map(callback -> (ToolCallback) new GuardedToolCallback(callback, guard))
                .toList();
    }
}
