package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvolutionWorkflowServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRequireReplayApprovalBeforeActivationAndSupportRollback() {
        Fixture fixture = fixture(true);
        EvolutionCandidate candidate = fixture.candidate();

        EvolutionCandidate evaluated = fixture.service().evaluate(candidate.getId());
        assertThat(evaluated.getStatus()).isEqualTo(EvolutionCandidateStatus.EVALUATED);
        assertThat(evaluated.getEvaluation().isPassed()).isTrue();

        EvolutionCandidate approved = fixture.service().approve(candidate.getId());
        assertThat(approved.getStatus()).isEqualTo(EvolutionCandidateStatus.APPROVED);

        EvolutionCandidate active = fixture.service().activate(candidate.getId());
        assertThat(active.getStatus()).isEqualTo(EvolutionCandidateStatus.ACTIVE);
        assertThat(active.getPreviousCandidateId()).isNull();
        assertThat(active.getPreviousContent()).isEqualTo("基线 Prompt");
        verify(fixture.artifactManager()).activate(active);

        EvolutionCandidate rolledBack = fixture.service().rollback(candidate.getId());
        assertThat(rolledBack.getStatus()).isEqualTo(EvolutionCandidateStatus.ROLLED_BACK);
        verify(fixture.artifactManager()).restore(rolledBack);
    }

    @Test
    void shouldBlockApprovalWhenReplayDoesNotMeetGate() {
        Fixture fixture = fixture(false);
        EvolutionCandidate evaluated = fixture.service().evaluate(fixture.candidate().getId());

        assertThat(evaluated.getStatus()).isEqualTo(EvolutionCandidateStatus.EVALUATED);
        assertThatThrownBy(() -> fixture.service().approve(evaluated.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未通过回放评估");
    }

    @Test
    void shouldRejectCandidateContainingCredential() {
        Fixture fixture = fixture(true);
        EvolutionCandidate unsafe = fixture.candidate();
        unsafe.setContent("password=unsafevalue123");
        fixture.candidateStore().save(unsafe);

        assertThatThrownBy(() -> fixture.service().evaluate(unsafe.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("安全检查");
    }

    @Test
    void shouldReplaceActivePromptAndRestorePreviousCandidateOnRollback() {
        Fixture fixture = fixture(true);
        when(fixture.artifactManager().currentContent(EvolutionArtifactType.PROMPT, "think.system.v1"))
                .thenReturn("基线 Prompt", "改进后的 Prompt");

        EvolutionCandidate first = evaluateApproveActivate(fixture, fixture.candidate());
        EvolutionCandidate second = fixture.candidateStore().save(EvolutionCandidate.builder()
                .type(EvolutionArtifactType.PROMPT)
                .targetName("think.system.v1")
                .baseVersion("think.system.v1")
                .candidateVersion("think.system.v1.candidate.second")
                .content("第二次改进后的 Prompt")
                .rationale("继续修复新的低分问题")
                .sourceTraceIds(first.getSourceTraceIds())
                .status(EvolutionCandidateStatus.DRAFT)
                .build());

        EvolutionCandidate secondActive = evaluateApproveActivate(fixture, second);
        assertThat(secondActive.getPreviousCandidateId()).isEqualTo(first.getId());
        assertThat(secondActive.getPreviousContent()).isEqualTo("改进后的 Prompt");
        assertThat(fixture.candidateStore().find(first.getId()).getStatus())
                .isEqualTo(EvolutionCandidateStatus.SUPERSEDED);

        EvolutionCandidate rolledBack = fixture.service().rollback(secondActive.getId());
        assertThat(rolledBack.getStatus()).isEqualTo(EvolutionCandidateStatus.ROLLED_BACK);
        assertThat(fixture.candidateStore().find(first.getId()).getStatus())
                .isEqualTo(EvolutionCandidateStatus.ACTIVE);

        EvolutionCandidate firstRolledBack = fixture.service().rollback(first.getId());
        assertThat(firstRolledBack.getStatus()).isEqualTo(EvolutionCandidateStatus.ROLLED_BACK);
        assertThat(firstRolledBack.getPreviousCandidateId()).isNull();
        assertThat(firstRolledBack.getPreviousContent()).isEqualTo("基线 Prompt");
    }

    @Test
    void shouldBuildSameVersionChainForSkill() {
        String firstSkill = """
                ---
                name: order-refund
                description: 退款流程
                allowedTools: [databaseQuery]
                ---

                先查询订单，再判断退款资格。
                """;
        String secondSkill = """
                ---
                name: order-refund
                description: 退款流程
                allowedTools: [databaseQuery]
                ---

                先查询订单和支付状态，再说明退款依据与下一步操作。
                """;
        Fixture fixture = fixture(true, EvolutionArtifactType.SKILL,
                "order-refund", "基线 Skill", firstSkill);
        when(fixture.artifactManager().currentContent(EvolutionArtifactType.SKILL, "order-refund"))
                .thenReturn("基线 Skill", firstSkill);

        EvolutionCandidate first = evaluateApproveActivate(fixture, fixture.candidate());
        EvolutionCandidate second = fixture.candidateStore().save(EvolutionCandidate.builder()
                .type(EvolutionArtifactType.SKILL)
                .targetName("order-refund")
                .baseVersion("order-refund")
                .candidateVersion("order-refund.candidate.second")
                .content(secondSkill)
                .rationale("补充支付状态与回答结构")
                .sourceTraceIds(first.getSourceTraceIds())
                .status(EvolutionCandidateStatus.DRAFT)
                .build());

        EvolutionCandidate secondActive = evaluateApproveActivate(fixture, second);
        assertThat(secondActive.getPreviousCandidateId()).isEqualTo(first.getId());
        assertThat(secondActive.getPreviousContent()).isEqualTo(firstSkill);
        assertThat(fixture.candidateStore().find(first.getId()).getStatus())
                .isEqualTo(EvolutionCandidateStatus.SUPERSEDED);
    }

    private EvolutionCandidate evaluateApproveActivate(Fixture fixture, EvolutionCandidate candidate) {
        fixture.service().evaluate(candidate.getId());
        fixture.service().approve(candidate.getId());
        return fixture.service().activate(candidate.getId());
    }

    private Fixture fixture(boolean passed) {
        return fixture(passed, EvolutionArtifactType.PROMPT,
                "think.system.v1", "基线 Prompt", "改进后的 Prompt");
    }

    private Fixture fixture(boolean passed,
                            EvolutionArtifactType type,
                            String targetName,
                            String baselineContent,
                            String candidateContent) {
        EvolutionProperties properties = new EvolutionProperties();
        properties.setStorageDir(tempDir.toString());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvolutionTraceStore traceStore = new EvolutionTraceStore(objectMapper, properties);
        EvolutionCandidateStore candidateStore = new EvolutionCandidateStore(objectMapper, properties);
        EvolutionTrace trace = traceStore.save(EvolutionTrace.builder()
                .messageId("message-1")
                .sessionId("session-1")
                .question("如何退款")
                .answer("旧回答")
                .promptVersion("think.system.v1")
                .toolsUsed(List.of())
                .skillsUsed(List.of())
                .averageScore(2.0)
                .lowScore(true)
                .build());
        EvolutionCandidate candidate = candidateStore.save(EvolutionCandidate.builder()
                .type(type)
                .targetName(targetName)
                .baseVersion(targetName)
                .candidateVersion(targetName + ".candidate.test")
                .content(candidateContent)
                .rationale("测试")
                .sourceTraceIds(List.of(trace.getId()))
                .status(EvolutionCandidateStatus.DRAFT)
                .build());
        EvolutionReplayEvaluator evaluator = (ignoredCandidate, ignoredTraces) -> EvolutionEvaluation.builder()
                .sampleCount(1)
                .baselineScore(2.0)
                .candidateScore(passed ? 4.0 : 2.1)
                .improvement(passed ? 2.0 : 0.1)
                .passed(passed)
                .judgeModel("test-judge")
                .summary("test")
                .evaluatedAt(Instant.now())
                .build();
        EvolutionArtifactManager artifactManager = mock(EvolutionArtifactManager.class);
        when(artifactManager.currentContent(type, targetName))
                .thenReturn(baselineContent);
        EvolutionWorkflowService service = new EvolutionWorkflowService(
                candidateStore, traceStore, evaluator,
                new EvolutionArtifactSafetyValidator(), artifactManager);
        return new Fixture(service, candidateStore, candidate, artifactManager);
    }

    private record Fixture(EvolutionWorkflowService service,
                           EvolutionCandidateStore candidateStore,
                           EvolutionCandidate candidate,
                           EvolutionArtifactManager artifactManager) {
    }
}
