package com.xmu.ShopAssistant.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolLoopGuardTest {
    private ToolLoopGuardProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ToolLoopGuardProperties();
        properties.setMaxConsecutiveSameSignature(3);
        properties.setMaxConsecutiveSameResult(3);
    }

    @Test
    void canonicalJsonArgumentsCountAsTheSameSignatureAndFourthCallIsBlocked() {
        ToolLoopGuard guard = new ToolLoopGuard(new ObjectMapper(), properties);
        ToolCallback delegate = callback("web_search", "结果一", "结果二", "结果三");
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guard);

        guarded.call("{\"query\":\"AI\",\"page\":1}");
        guarded.call("{ \"page\" : 1, \"query\" : \"AI\" }");
        guarded.call("{\"page\":1,\"query\":\"AI\"}");
        String fourth = guarded.call("{\"query\":\"AI\",\"page\":1}");

        assertTrue(fourth.contains("工具循环保护已熔断"));
        verify(delegate, times(3)).call(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void structurallyEqualJsonResultsFuseOnTheThirdConsecutiveResult() {
        ToolLoopGuard guard = new ToolLoopGuard(new ObjectMapper(), properties);
        ToolCallback delegate = callback(
                "web_search",
                "{\"items\":[1,2],\"total\":2}",
                "{ \"total\": 2, \"items\": [1, 2] }",
                "{\"total\":2,\"items\":[1,2]}"
        );
        GuardedToolCallback guarded = new GuardedToolCallback(delegate, guard);

        guarded.call("{\"query\":\"A\"}");
        guarded.call("{\"query\":\"B\"}");
        String third = guarded.call("{\"query\":\"C\"}");

        assertTrue(guard.isFused());
        assertTrue(third.contains("连续 3 次返回相同结果"));
    }

    @Test
    void differentIntermediateResultResetsConsecutiveResultCounter() {
        ToolLoopGuard guard = new ToolLoopGuard(new ObjectMapper(), properties);

        guard.beforeCall("web_search", "A");
        guard.afterCall("web_search", "same result");
        guard.beforeCall("web_search", "B");
        guard.afterCall("web_search", "same   result");
        guard.beforeCall("web_search", "C");
        guard.afterCall("web_search", "different result");
        guard.beforeCall("web_search", "D");
        guard.afterCall("web_search", "same result");

        assertFalse(guard.isFused());
        assertEquals(
                guard.normalizeResult("line 1\r\nline 2"),
                guard.normalizeResult(" line 1  line 2 ")
        );
    }

    private ToolCallback callback(String name, String... results) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn(name);
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(org.mockito.ArgumentMatchers.anyString())).thenReturn(results[0], remaining(results));
        return callback;
    }

    private String[] remaining(String[] values) {
        if (values.length <= 1) {
            return new String[0];
        }
        return java.util.Arrays.copyOfRange(values, 1, values.length);
    }
}
