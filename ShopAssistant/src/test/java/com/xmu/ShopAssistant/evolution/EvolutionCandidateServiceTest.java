package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvolutionCandidateServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldGenerateDraftPromptCandidateOnlyFromLowScoreTraces() {
        EvolutionProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvolutionTraceStore traceStore = new EvolutionTraceStore(objectMapper, properties);
        EvolutionCandidateStore candidateStore = new EvolutionCandidateStore(objectMapper, properties);
        PromptRegistry promptRegistry = mock(PromptRegistry.class);
        when(promptRegistry.get("think.system.v1")).thenReturn("当前 Prompt {kbs}");
        SkillRegistry skillRegistry = mock(SkillRegistry.class);
        EvolutionCandidateGenerator generator = (type, target, base, traces) -> {
            assertThat(type).isEqualTo(EvolutionArtifactType.PROMPT);
            assertThat(target).isEqualTo("think.system.v1");
            assertThat(base).contains("{kbs}");
            assertThat(traces).hasSize(1);
            return new EvolutionCandidateGenerator.Proposal("改进 Prompt {kbs}", "修复低分回答的遗漏");
        };
        EvolutionCandidateService service = new EvolutionCandidateService(
                properties, traceStore, candidateStore, generator, promptRegistry, skillRegistry);
        EvolutionTrace low = traceStore.save(trace(true));

        EvolutionCandidateService.GenerateRequest request = new EvolutionCandidateService.GenerateRequest();
        request.setType(EvolutionArtifactType.PROMPT);
        request.setTraceIds(List.of(low.getId()));
        EvolutionCandidate candidate = service.generate(request);

        assertThat(candidate.getStatus()).isEqualTo(EvolutionCandidateStatus.DRAFT);
        assertThat(candidate.getContent()).isEqualTo("改进 Prompt {kbs}");
        assertThat(candidate.getSourceTraceIds()).containsExactly(low.getId());
        assertThat(candidateStore.find(candidate.getId())).isEqualTo(candidate);
        assertThat(service.list(10)).extracting(EvolutionCandidate::getId).containsExactly(candidate.getId());
    }

    @Test
    void shouldRejectNonLowScoreTrace() {
        EvolutionProperties properties = properties();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvolutionTraceStore traceStore = new EvolutionTraceStore(objectMapper, properties);
        EvolutionTrace normal = traceStore.save(trace(false));
        EvolutionCandidateService service = new EvolutionCandidateService(
                properties,
                traceStore,
                new EvolutionCandidateStore(objectMapper, properties),
                mock(EvolutionCandidateGenerator.class),
                mock(PromptRegistry.class),
                mock(SkillRegistry.class));
        EvolutionCandidateService.GenerateRequest request = new EvolutionCandidateService.GenerateRequest();
        request.setTraceIds(List.of(normal.getId()));

        assertThatThrownBy(() -> service.generate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是低分样本");
    }

    private EvolutionProperties properties() {
        EvolutionProperties properties = new EvolutionProperties();
        properties.setStorageDir(tempDir.toString());
        return properties;
    }

    private static EvolutionTrace trace(boolean lowScore) {
        return EvolutionTrace.builder()
                .messageId(lowScore ? "low" : "normal")
                .sessionId("session-1")
                .question("如何退款")
                .answer("回答")
                .promptVersion("think.system.v1")
                .toolsUsed(List.of("useSkill"))
                .skillsUsed(List.of("order-refund"))
                .averageScore(lowScore ? 2.0 : 4.0)
                .lowScore(lowScore)
                .build();
    }
}
