package com.xmu.ShopAssistant.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.service.SseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentCheckpointServiceTest {
    @TempDir
    Path tempDir;

    private AgentCheckpointStore store;
    private AgentCheckpointService service;
    private ApplicationEventPublisher publisher;

    @BeforeEach
    void setUp() {
        AgentCheckpointProperties properties = new AgentCheckpointProperties();
        properties.setStorageDir(tempDir.toString());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        store = new AgentCheckpointStore(objectMapper, properties);
        publisher = mock(ApplicationEventPublisher.class);
        service = new AgentCheckpointService(
                store,
                properties,
                mock(SseService.class),
                publisher
        );
    }

    @Test
    void dangerousToolShouldPersistAndWaitForApproval() throws Exception {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "deleteFile", "{\"path\":\"sandbox/a.txt\"}"
        );

        AgentCheckpoint checkpoint = service.pauseIfApprovalRequired(
                "session-1",
                "agent-1",
                null,
                AgentRuntimeType.SINGLE,
                List.of(call),
                Set.of("deleteFile")
        ).orElseThrow();

        assertEquals(AgentCheckpointStatus.WAITING_APPROVAL, checkpoint.getStatus());
        assertEquals("{\"path\":\"sandbox/a.txt\"}", checkpoint.getToolCalls().get(0).getArguments());
        assertTrue(Files.readString(
                tempDir.resolve(checkpoint.getId() + ".checkpoint.json"),
                StandardCharsets.UTF_8
        ).contains("WAITING_APPROVAL"));
    }

    @Test
    void approvalCanOnlyBeConsumedOnce() {
        AgentCheckpoint checkpoint = createWaitingCheckpoint();

        service.approve(checkpoint.getId());

        assertEquals(AgentCheckpointStatus.APPROVED, service.require(checkpoint.getId()).getStatus());
        verify(publisher).publishEvent(new CheckpointDecisionEvent(checkpoint.getId()));
        assertThrows(IllegalStateException.class, () -> service.approve(checkpoint.getId()));
    }

    private AgentCheckpoint createWaitingCheckpoint() {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                "call-1", "function", "sendEmail", "{\"to\":\"test@example.com\"}"
        );
        return service.pauseIfApprovalRequired(
                "session-1",
                "agent-1",
                null,
                AgentRuntimeType.SINGLE,
                List.of(call),
                Set.of("sendEmail")
        ).orElseThrow();
    }
}
