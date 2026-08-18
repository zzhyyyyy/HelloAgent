package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvolutionTraceStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistQueryAndIgnoreBrokenTraceFiles() throws Exception {
        EvolutionProperties properties = new EvolutionProperties();
        properties.setStorageDir(tempDir.toString());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        EvolutionTraceStore store = new EvolutionTraceStore(objectMapper, properties);

        EvolutionTrace lowScore = store.save(trace("message-low", 2.0, true));
        EvolutionTrace highScore = store.save(trace("message-high", 4.5, false));
        Files.writeString(tempDir.resolve("traces/broken.json"), "{broken", StandardCharsets.UTF_8);

        assertThat(lowScore.getId()).isNotBlank();
        assertThat(store.find(lowScore.getId())).extracting(EvolutionTrace::getMessageId)
                .isEqualTo("message-low");
        assertThat(store.list(true, 10)).extracting(EvolutionTrace::getMessageId)
                .containsExactly("message-low");
        assertThat(store.list(false, 10)).extracting(EvolutionTrace::getMessageId)
                .containsExactlyInAnyOrder("message-low", "message-high");
        assertThat(store.find("../invalid")).isNull();
        assertThat(Files.exists(tempDir.resolve("traces/" + highScore.getId() + ".json.tmp"))).isFalse();
    }

    private static EvolutionTrace trace(String messageId, double averageScore, boolean lowScore) {
        return EvolutionTrace.builder()
                .messageId(messageId)
                .sessionId("session-1")
                .question("question")
                .answer("answer")
                .promptVersion("think.system.v1")
                .toolsUsed(List.of())
                .skillsUsed(List.of())
                .evalScore(ChatMessageDTO.EvalScore.builder()
                        .correctness((int) averageScore)
                        .completeness((int) averageScore)
                        .relevance((int) averageScore)
                        .build())
                .averageScore(averageScore)
                .lowScore(lowScore)
                .build();
    }
}
