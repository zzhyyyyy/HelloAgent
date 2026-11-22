package com.xmu.ShopAssistant.memory;

import com.xmu.ShopAssistant.converter.ChatMessageConverter;
import com.xmu.ShopAssistant.mapper.ChatMessageMapper;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.entity.ChatMessage;
import com.xmu.ShopAssistant.service.AgentMemoryService;
import com.xmu.ShopAssistant.service.SessionSummaryService;
import com.xmu.ShopAssistant.service.impl.ChatMessageFacadeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatMessageMemoryCaptureTest {

    @Test
    void assistantAndToolMessagesShouldEnterUnifiedEventPipeline() throws Exception {
        ChatMessageMapper messageMapper = mock(ChatMessageMapper.class);
        ChatMessageConverter converter = mock(ChatMessageConverter.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        AgentMemoryService legacyMemory = mock(AgentMemoryService.class);
        SessionSummaryService summaryService = mock(SessionSummaryService.class);
        MemoryEventService eventService = mock(MemoryEventService.class);
        ChatMessageFacadeServiceImpl facade = new ChatMessageFacadeServiceImpl(
                messageMapper, converter, publisher, legacyMemory, summaryService, eventService);

        ChatMessageDTO dto = ChatMessageDTO.builder()
                .sessionId("session-a")
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content("最终回答")
                .build();
        ChatMessage entity = ChatMessage.builder()
                .id("message-a").sessionId("session-a").role("assistant").content("最终回答").build();
        when(converter.toEntity(dto)).thenReturn(entity);
        when(messageMapper.insert(entity)).thenReturn(1);

        String messageId = facade.createChatMessage(dto).getChatMessageId();

        assertThat(messageId).isEqualTo("message-a");
        verify(eventService).captureChatMessage(null, entity);
        verifyNoInteractions(publisher);
    }
}
