package com.xmu.ShopAssistant.service;

import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.request.CreateChatMessageRequest;
import com.xmu.ShopAssistant.model.request.UpdateChatMessageRequest;
import com.xmu.ShopAssistant.model.response.CreateChatMessageResponse;
import com.xmu.ShopAssistant.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    ChatMessageDTO getChatMessageById(String chatMessageId);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
