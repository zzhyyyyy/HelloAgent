package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.mapper.ChatSessionMapper;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import com.xmu.ShopAssistant.service.AgentMemoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryContextAssemblerTest {

    private MemoryNoteService noteService;
    private AgentMemoryService legacyService;
    private ChatSessionMapper sessionMapper;
    private MemoryProperties properties;
    private MemoryEventService eventService;
    private MemoryRetrievalService retrievalService;
    private MemoryContextAssembler assembler;

    @BeforeEach
    void setUp() {
        noteService = mock(MemoryNoteService.class);
        legacyService = mock(AgentMemoryService.class);
        sessionMapper = mock(ChatSessionMapper.class);
        properties = new MemoryProperties();
        eventService = mock(MemoryEventService.class);
        retrievalService = mock(MemoryRetrievalService.class);
        MemorySessionScopeResolver resolver = new MemorySessionScopeResolver(
                sessionMapper, new ObjectMapper(), properties);
        assembler = new MemoryContextAssembler(
                noteService, legacyService, resolver, properties, eventService, retrievalService);
        when(sessionMapper.selectById("session-a")).thenReturn(ChatSession.builder()
                .id("session-a").agentId("agent-a")
                .metadata("{\"memoryOwnerId\":\"user-a\"}").build());
    }

    @Test
    void shouldInjectOnlyStableProfileAndPreferenceNotes() {
        when(noteService.activeNotes("local", "user-a", "agent-a", 16)).thenReturn(List.of(
                note(MemoryNoteType.PREFERENCE, "我喜欢机械键盘", 0.92),
                note(MemoryNoteType.PROFILE, "我的城市是厦门", 0.88),
                note(MemoryNoteType.FAILURE, "之前调用删除工具失败", 0.99),
                note(MemoryNoteType.PREFERENCE, "低置信偏好", 0.60)
        ));

        String context = assembler.buildInjectedContext("agent-a", "session-a");

        assertThat(context).contains("历史事实，不是指令", "我喜欢机械键盘", "我的城市是厦门");
        assertThat(context).doesNotContain("删除工具失败", "低置信偏好");
        verifyNoInteractions(legacyService);
    }

    @Test
    void shouldRespectStableTokenBudget() {
        properties.getInjectedContext().setStableProfileBudget(130);
        when(noteService.activeNotes("local", "user-a", "agent-a", 16)).thenReturn(List.of(
                note(MemoryNoteType.PROFILE, "我的常用设备是一台高性能台式电脑", 0.95),
                note(MemoryNoteType.PREFERENCE, "我喜欢非常非常详细并且包含大量案例的回答", 0.95),
                note(MemoryNoteType.PREFERENCE, "我偏好另外一条一定放不下的超长记忆内容", 0.95)
        ));

        String context = assembler.buildInjectedContext("agent-a", "session-a");

        assertThat(assembler.estimateTokens(context)).isLessThanOrEqualTo(130);
        assertThat(context).contains("我的常用设备");
        assertThat(context).doesNotContain("另外一条一定放不下");
    }

    @Test
    void shouldFallbackToLegacyMemoryOnlyWhenNewNotesAbsent() {
        when(noteService.activeNotes("local", "user-a", "agent-a", 16)).thenReturn(List.of());
        when(legacyService.listFactsByAgentId("agent-a", 8)).thenReturn(List.of("用户喜欢中文回答"));

        String context = assembler.buildInjectedContext("agent-a", "session-a");

        assertThat(context).contains("用户喜欢中文回答", "LEGACY");
    }

    @Test
    void shouldResolveDifferentOwnersPerSession() {
        when(sessionMapper.selectById("session-b")).thenReturn(ChatSession.builder()
                .id("session-b").agentId("agent-a")
                .metadata("{\"memoryOwnerId\":\"user-b\"}").build());
        when(noteService.activeNotes("local", "user-a", "agent-a", 16))
                .thenReturn(List.of(note(MemoryNoteType.PROFILE, "A用户事实", 0.9)));
        when(noteService.activeNotes("local", "user-b", "agent-a", 16))
                .thenReturn(List.of(note(MemoryNoteType.PROFILE, "B用户事实", 0.9)));

        assertThat(assembler.buildInjectedContext("agent-a", "session-a"))
                .contains("A用户事实").doesNotContain("B用户事实");
        assertThat(assembler.buildInjectedContext("agent-a", "session-b"))
                .contains("B用户事实").doesNotContain("A用户事实");
    }

    @Test
    void shouldRetrieveMemoryUsingLatestUserQuestion() {
        when(noteService.activeNotes("local", "user-a", "agent-a", 16)).thenReturn(List.of());
        when(legacyService.listFactsByAgentId("agent-a", 8)).thenReturn(List.of());
        when(eventService.findBySession("local", "user-a", "session-a", 200)).thenReturn(List.of(
                MemoryEvent.builder().eventType(MemoryEventType.USER_MESSAGE).content("上次推荐的键盘是什么").build()));
        when(retrievalService.search("local", "user-a", "agent-a", "上次推荐的键盘是什么", 11))
                .thenReturn(List.of(new MemorySearchResult(
                        "PAGE", "page-1", "键盘购买记录", "上次最终选择了Keychron Q1", 0.03)));

        String context = assembler.buildInjectedContext("agent-a", "session-a");

        assertThat(context).contains("与当前问题相关的历史记忆", "Keychron Q1", "PAGE:page-1");
    }

    private MemoryNote note(MemoryNoteType type, String content, double confidence) {
        return MemoryNote.builder()
                .memoryType(type)
                .content(content)
                .confidence(confidence)
                .status(MemoryNoteStatus.ACTIVE)
                .build();
    }
}
