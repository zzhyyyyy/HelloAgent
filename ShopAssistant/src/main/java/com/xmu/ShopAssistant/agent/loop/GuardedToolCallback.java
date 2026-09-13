package com.xmu.ShopAssistant.agent.loop;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** 在不改变 ToolDefinition 的前提下，为本地工具和 MCP 工具统一增加循环熔断。 */
final class GuardedToolCallback implements ToolCallback {
    private final ToolCallback delegate;
    private final ToolLoopGuard loopGuard;

    GuardedToolCallback(ToolCallback delegate, ToolLoopGuard loopGuard) {
        this.delegate = delegate;
        this.loopGuard = loopGuard;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return invoke(toolInput, () -> delegate.call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return invoke(toolInput, () -> delegate.call(toolInput, toolContext));
    }

    private String invoke(String arguments, ToolInvocation invocation) {
        String toolName = getToolDefinition().name();
        ToolLoopGuard.CallDecision decision = loopGuard.beforeCall(toolName, arguments);
        if (!decision.allowed()) {
            return decision.response();
        }
        String result = invocation.call();
        return loopGuard.afterCall(toolName, result);
    }

    @FunctionalInterface
    private interface ToolInvocation {
        String call();
    }
}
