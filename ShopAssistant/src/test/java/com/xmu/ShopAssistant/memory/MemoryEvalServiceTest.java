package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryEvalServiceTest {

    @Test
    void shouldEvaluateMockDatasetForRecallAndLeakage() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<MemoryEvalCase> cases;
        try (InputStream input = getClass().getResourceAsStream("/memory/memory-eval-mock.json")) {
            cases = objectMapper.readValue(input, new TypeReference<>() {});
        }

        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.search("local", "user-a", "agent-a", "我之前喜欢什么类型的键盘？", 5))
                .thenReturn(List.of(
                        result("page-keyboard", "键盘购买记录", "用户喜欢机械键盘，最终选择Keychron Q1")));
        when(retrieval.search("local", "user-b", "agent-a", "我喜欢什么颜色？", 5))
                .thenReturn(List.of(result("note-color-b", "颜色偏好", "用户喜欢蓝色")));
        when(retrieval.search("local", "user-a", "agent-a", "我上次确定的键盘预算是多少？", 5))
                .thenReturn(List.of(result("note-budget", "键盘预算", "预算确定为1000元")));

        MemoryEvalReport report = new MemoryEvalServiceImpl(retrieval).evaluate(cases, 5);

        assertThat(report.getTotalCases()).isEqualTo(3);
        assertThat(report.getPassedCases()).isEqualTo(3);
        assertThat(report.getCasePassRate()).isEqualTo(1.0);
        assertThat(report.getFactRecall()).isEqualTo(1.0);
        assertThat(report.getLeakageRate()).isZero();
        assertThat(report.getResults()).allMatch(MemoryEvalResult::isPassed);
    }

    @Test
    void shouldFailCaseWhenCrossOwnerFactLeaks() {
        MemoryRetrievalService retrieval = mock(MemoryRetrievalService.class);
        when(retrieval.search("local", "user-b", "agent-a", "颜色", 5))
                .thenReturn(List.of(result("bad-page", "错误混合画像", "B喜欢蓝色，但A用户喜欢红色")));
        MemoryEvalCase evalCase = new MemoryEvalCase(
                "leak", "local", "user-b", "agent-a", "颜色",
                List.of("蓝色"), List.of("A用户喜欢红色"));

        MemoryEvalReport report = new MemoryEvalServiceImpl(retrieval).evaluate(List.of(evalCase), 5);

        assertThat(report.getPassedCases()).isZero();
        assertThat(report.getLeakageRate()).isEqualTo(1.0);
        assertThat(report.getResults().get(0).getLeakedFacts()).containsExactly("A用户喜欢红色");
    }

    private MemorySearchResult result(String id, String title, String content) {
        return new MemorySearchResult("PAGE", id, title, content, 0.1);
    }
}
