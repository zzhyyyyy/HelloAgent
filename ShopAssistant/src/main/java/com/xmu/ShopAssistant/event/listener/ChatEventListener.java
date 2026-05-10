package com.xmu.ShopAssistant.event.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.xmu.ShopAssistant.agent.ShopAi;
import com.xmu.ShopAssistant.agent.ShopAiFactory;
import com.xmu.ShopAssistant.converter.ChatSessionConverter;
import com.xmu.ShopAssistant.event.ChatEvent;
import com.xmu.ShopAssistant.mapper.ChatSessionMapper;
import com.xmu.ShopAssistant.model.dto.ChatSessionDTO;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final ShopAiFactory shopAiFactory;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionConverter chatSessionConverter;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        String mode = getSessionMode(event.getSessionId());

        if ("MULTI".equals(mode)) {
            log.info("多 Agent 模式: sessionId={}, agentId={}", event.getSessionId(), event.getAgentId());
            ShopAi supervisor = shopAiFactory.createSupervisor(event.getAgentId(), event.getSessionId());
            supervisor.run();
        } else {
            log.info("单 Agent 模式: sessionId={}, agentId={}", event.getSessionId(), event.getAgentId());
            ShopAi shopAi = shopAiFactory.create(event.getAgentId(), event.getSessionId());
            shopAi.run();
        }
    }

    private String getSessionMode(String sessionId) {
        try {
            ChatSession chatSession = chatSessionMapper.selectById(sessionId);
            if (chatSession == null || chatSession.getMetadata() == null) {
                return "SINGLE";
            }
            ChatSessionDTO.MetaData metadata = chatSessionConverter.deserializeMetadata(chatSession.getMetadata());
            return metadata != null && metadata.getMode() != null ? metadata.getMode() : "SINGLE";
        } catch (Exception e) {
            log.warn("读取会话模式失败，默认 SINGLE: sessionId={}", sessionId, e);
            return "SINGLE";
        }
    }
}
