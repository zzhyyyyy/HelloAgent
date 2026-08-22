package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class EvolutionArtifactManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistRestoreAndRollbackActivePrompt() throws Exception {
        EvolutionProperties properties = new EvolutionProperties();
        properties.setStorageDir(tempDir.toString());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        PromptRegistry firstRegistry = mock(PromptRegistry.class);
        EvolutionArtifactManager firstManager = new EvolutionArtifactManager(
                objectMapper, firstRegistry, mock(SkillRegistry.class), properties);
        EvolutionCandidate candidate = EvolutionCandidate.builder()
                .id("candidate-1")
                .type(EvolutionArtifactType.PROMPT)
                .targetName("think.system.v1")
                .content("候选 Prompt")
                .previousCandidateId("candidate-0")
                .previousContent("基线 Prompt")
                .build();

        firstManager.activate(candidate);
        verify(firstRegistry).activate("think.system.v1", "候选 Prompt");

        PromptRegistry restartedRegistry = mock(PromptRegistry.class);
        EvolutionArtifactManager restartedManager = new EvolutionArtifactManager(
                objectMapper, restartedRegistry, mock(SkillRegistry.class), properties);
        restartedManager.restoreActiveArtifacts();
        verify(restartedRegistry).activate("think.system.v1", "候选 Prompt");

        restartedManager.restore(candidate);
        verify(restartedRegistry).activate("think.system.v1", "基线 Prompt");

        Path activeFile = tempDir.resolve("active").resolve("prompt-think.system.v1.json");
        assertThat(Files.isRegularFile(activeFile)).isTrue();
        EvolutionActiveArtifact restored = objectMapper.readValue(activeFile.toFile(), EvolutionActiveArtifact.class);
        assertThat(restored.getCandidateId()).isEqualTo("candidate-0");
        assertThat(restored.getContent()).isEqualTo("基线 Prompt");
    }
}
