package com.xmu.ShopAssistant.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.agent.ShopAiFactory;
import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import com.xmu.ShopAssistant.service.SseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CheckpointExecutionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void approvedCheckpointExecutesPersistedArgumentsExactlyOnce() {
        AgentCheckpointProperties properties = new AgentCheckpointProperties();
        properties.setStorageDir(tempDir.toString());
        AgentCheckpointStore store = new AgentCheckpointStore(
                new ObjectMapper().findAndRegisterModules(),
                properties
        );
        SseService sseService = mock(SseService.class);
        AgentCheckpointService checkpointService = new AgentCheckpointService(
                store,
                properties,
                sseService,
                mock(ApplicationEventPublisher.class)
        );

        String arguments = "{\"sql\":\"delete from test where id=7\"}";
        AgentCheckpoint checkpoint = checkpointService.pauseIfApprovalRequired(
                "session-1",
                "agent-1",
                null,
                AgentRuntimeType.SINGLE,
                List.of(new AssistantMessage.ToolCall("call-7", "function", "executeSql", arguments)),
                Set.of("executeSql")
        ).orElseThrow();
        checkpointService.approve(checkpoint.getId());

        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("executeSql");
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(arguments)).thenReturn("删除成功");

        ShopAiFactory factory = mock(ShopAiFactory.class);
        when(factory.resolveToolCallbacksForCheckpoint("agent-1")).thenReturn(List.of(callback));
        ChatMessageFacadeService messageService = mock(ChatMessageFacadeService.class);
        when(messageService.createChatMessage(any(com.xmu.ShopAssistant.model.dto.ChatMessageDTO.class)))
                .thenReturn(CreateChatMessageResponse.builder().chatMessageId("message-1").build());
        ChatMessageConverter converter = mock(ChatMessageConverter.class);
        when(converter.toVO(any(com.xmu.ShopAssistant.model.dto.ChatMessageDTO.class)))
                .thenReturn(ChatMessageVO.builder().id("message-1").build());

        CheckpointExecutionService executionService = new CheckpointExecutionService(
                checkpointService,
                factory,
                messageService,
                converter,
                sseService
        );

        assertEquals(true, executionService.processDecision(checkpoint.getId()));
        verify(callback).call(arguments);
        assertEquals(AgentCheckpointStatus.TOOL_COMPLETED, checkpointService.require(checkpoint.getId()).getStatus());
        assertEquals(true, executionService.processDecision(checkpoint.getId()));
        assertEquals(true, checkpointService.claimContinuation(checkpoint.getId()).isPresent());
        assertEquals(false, checkpointService.claimContinuation(checkpoint.getId()).isPresent());

        AgentCheckpoint rejected = checkpointService.pauseIfApprovalRequired(
                "session-1",
                "agent-1",
                null,
                AgentRuntimeType.SINGLE,
                List.of(new AssistantMessage.ToolCall("call-8", "function", "executeSql", arguments)),
                Set.of("executeSql")
        ).orElseThrow();
        checkpointService.reject(rejected.getId());
        assertEquals(true, executionService.processDecision(rejected.getId()));
        assertEquals(
                AgentCheckpointStatus.REJECTION_COMPLETED,
                checkpointService.require(rejected.getId()).getStatus()
        );
        assertEquals(true, executionService.processDecision(rejected.getId()));
        assertEquals(true, checkpointService.claimContinuation(rejected.getId()).isPresent());
        assertEquals(false, executionService.processDecision(rejected.getId()));
    }
}
