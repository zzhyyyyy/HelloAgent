package com.xmu.ShopAssistant.memory;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryManagementControllerTest {

    @Test
    void shouldExposeDreamReviewSearchRollbackAndEvalOperations() {
        MemoryDreamService dreamService = mock(MemoryDreamService.class);
        MemoryRetrievalService retrievalService = mock(MemoryRetrievalService.class);
        MemoryEvalService evalService = mock(MemoryEvalService.class);
        MemoryManagementController controller = new MemoryManagementController(
                dreamService, retrievalService, evalService);

        MemoryManagementController.DreamRequest dream = new MemoryManagementController.DreamRequest();
        dream.setTenantId("tenant");
        dream.setOwnerId("user-a");
        dream.setAgentId("agent-a");
        dream.setInstructions("整理购物偏好");
        when(dreamService.submit("tenant", "user-a", "agent-a", "整理购物偏好"))
                .thenReturn("job-1");
        assertThat(controller.submitDream(dream)).containsEntry("jobId", "job-1");

        controller.approve("proposal-1");
        controller.reject("proposal-2");
        controller.activate("snapshot-2");
        verify(dreamService).approveProposal("proposal-1");
        verify(dreamService).rejectProposal("proposal-2");
        verify(dreamService).activate("snapshot-2");

        MemorySnapshot previous = MemorySnapshot.builder().id("snapshot-1").build();
        when(dreamService.rollback("snapshot-2")).thenReturn(previous);
        assertThat(controller.rollback("snapshot-2")).isSameAs(previous);

        List<MemorySearchResult> results = List.of(
                new MemorySearchResult("PAGE", "page-1", "购物记录", "买了键盘", 0.1));
        when(retrievalService.search("tenant", "user-a", "agent-a", "键盘", 5)).thenReturn(results);
        assertThat(controller.search("tenant", "user-a", "agent-a", "键盘", 5)).isEqualTo(results);

        MemoryEvalCase evalCase = new MemoryEvalCase(
                "case-1", "tenant", "user-a", "agent-a", "键盘", List.of("键盘"), List.of());
        MemoryEvalReport report = MemoryEvalReport.builder().totalCases(1).passedCases(1).build();
        when(evalService.evaluate(List.of(evalCase), 5)).thenReturn(report);
        MemoryManagementController.EvalRequest evalRequest = new MemoryManagementController.EvalRequest();
        evalRequest.setCases(List.of(evalCase));
        evalRequest.setRetrievalLimit(5);
        assertThat(controller.evaluate(evalRequest)).isSameAs(report);
    }
}
