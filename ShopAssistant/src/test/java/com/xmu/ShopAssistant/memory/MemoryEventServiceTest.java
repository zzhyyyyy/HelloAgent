package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.mapper.ChatSessionMapper;
import com.xmu.ShopAssistant.model.entity.ChatMessage;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryEventServiceTest {

    private MemoryEventMapper eventMapper;
    private ChatSessionMapper sessionMapper;
    private MemoryProperties properties;
    private MemoryNoteService noteService;
    private MemoryEventServiceImpl service;

    @BeforeEach
    void setUp() {
        eventMapper = mock(MemoryEventMapper.class);
        sessionMapper = mock(ChatSessionMapper.class);
        properties = new MemoryProperties();
        noteService = mock(MemoryNoteService.class);
        MemorySessionScopeResolver resolver = new MemorySessionScopeResolver(sessionMapper, new ObjectMapper(), properties);
        service = new MemoryEventServiceImpl(eventMapper, new ObjectMapper(), properties, noteService, resolver);
        when(eventMapper.insert(any())).thenReturn(1);
    }

    @Test
    void shouldCaptureUserMessageWithSessionOwner() {
        when(sessionMapper.selectById("session-a")).thenReturn(session(
                "agent-a", "{\"memoryOwnerId\":\"user-a\",\"mode\":\"SINGLE\"}"));

        service.captureChatMessage("agent-a", message("message-a", "session-a", "user", "我喜欢简洁回答", null));

        ArgumentCaptor<MemoryEvent> captor = ArgumentCaptor.forClass(MemoryEvent.class);
        verify(eventMapper).insert(captor.capture());
        MemoryEvent event = captor.getValue();
        assertThat(event.getOwnerId()).isEqualTo("user-a");
        assertThat(event.getTenantId()).isEqualTo("local");
        assertThat(event.getEventType()).isEqualTo(MemoryEventType.USER_MESSAGE);
        assertThat(event.getTrustLevel()).isEqualTo(MemoryTrustLevel.USER_ASSERTED);
        assertThat(event.getContent()).isEqualTo("我喜欢简洁回答");
        verify(noteService).observe(event);
    }

    @Test
    void shouldSplitAssistantToolCallsIntoIndependentEvents() {
        when(sessionMapper.selectById("session-a")).thenReturn(session("agent-a", "{\"memoryOwnerId\":\"user-a\"}"));
        String metadata = """
                {"agentRole":"supervisor","toolCalls":[
                  {"id":"call-1","name":"web_search","arguments":"{\\\"q\\\":\\\"耳机\\\"}"},
                  {"id":"call-2","name":"readFile","arguments":"{\\\"filePath\\\":\\\"a.md\\\"}"}
                ]}
                """;

        service.captureChatMessage(null, message("message-b", "session-a", "assistant", "", metadata));

        ArgumentCaptor<MemoryEvent> captor = ArgumentCaptor.forClass(MemoryEvent.class);
        verify(eventMapper, times(3)).insert(captor.capture());
        List<MemoryEvent> events = captor.getAllValues();
        assertThat(events).extracting(MemoryEvent::getEventType)
                .containsExactly(MemoryEventType.ASSISTANT_MESSAGE,
                        MemoryEventType.TOOL_CALL, MemoryEventType.TOOL_CALL);
        assertThat(events.get(1).getEventKey()).isEqualTo("tool-call:call-1");
        assertThat(events.get(1).getStructuredPayload()).contains("web_search", "耳机");
        assertThat(events.get(0).getAgentRole()).isEqualTo("supervisor");
    }

    @Test
    void shouldKeepOwnerQueriesStrictlySeparated() {
        when(eventMapper.selectByOwner("tenant-a", "user-a", 20)).thenReturn(List.of(
                MemoryEvent.builder().tenantId("tenant-a").ownerId("user-a").content("A的偏好").build()));
        when(eventMapper.selectByOwner("tenant-a", "user-b", 20)).thenReturn(List.of(
                MemoryEvent.builder().tenantId("tenant-a").ownerId("user-b").content("B的偏好").build()));

        List<MemoryEvent> userA = service.findByOwner("tenant-a", "user-a", 20);
        List<MemoryEvent> userB = service.findByOwner("tenant-a", "user-b", 20);

        assertThat(userA).extracting(MemoryEvent::getContent).containsExactly("A的偏好");
        assertThat(userB).extracting(MemoryEvent::getContent).containsExactly("B的偏好");
        verify(eventMapper).selectByOwner("tenant-a", "user-a", 20);
        verify(eventMapper).selectByOwner("tenant-a", "user-b", 20);
    }

    @Test
    void shouldUseConfiguredFallbackForLegacySession() {
        properties.setDefaultTenantId("demo-tenant");
        properties.setDefaultOwnerId("legacy-user");
        when(sessionMapper.selectById("legacy-session")).thenReturn(session("agent-a", null));

        service.captureChatMessage(null,
                message("message-c", "legacy-session", "tool", "外部工具返回", "{\"toolResponse\":{}}"));

        ArgumentCaptor<MemoryEvent> captor = ArgumentCaptor.forClass(MemoryEvent.class);
        verify(eventMapper).insert(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo("legacy-user");
        assertThat(captor.getValue().getTenantId()).isEqualTo("demo-tenant");
        assertThat(captor.getValue().getTrustLevel()).isEqualTo(MemoryTrustLevel.EXTERNAL_UNTRUSTED);
        verifyNoInteractions(noteService);
    }

    private ChatSession session(String agentId, String metadata) {
        return ChatSession.builder().id("session-a").agentId(agentId).metadata(metadata).build();
    }

    private ChatMessage message(String id, String sessionId, String role, String content, String metadata) {
        return ChatMessage.builder()
                .id(id)
                .sessionId(sessionId)
                .role(role)
                .content(content)
                .metadata(metadata)
                .createdAt(LocalDateTime.of(2026, 11, 22, 10, 0))
                .build();
    }
}
