package com.xmu.ShopAssistant.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.model.dto.ChatSessionDTO;
import com.xmu.ShopAssistant.model.entity.ChatSession;
import com.xmu.ShopAssistant.model.request.CreateChatSessionRequest;
import com.xmu.ShopAssistant.model.request.UpdateChatSessionRequest;
import com.xmu.ShopAssistant.model.vo.ChatSessionVO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

@Component
@AllArgsConstructor
public class ChatSessionConverter {

    private final ObjectMapper objectMapper;

    public ChatSession toEntity(ChatSessionDTO chatSessionDTO) throws JsonProcessingException {
        Assert.notNull(chatSessionDTO, "ChatSessionDTO cannot be null");

        return ChatSession.builder()
                .id(chatSessionDTO.getId())
                .agentId(chatSessionDTO.getAgentId())
                .title(chatSessionDTO.getTitle())
                .metadata(chatSessionDTO.getMetadata() != null 
                        ? objectMapper.writeValueAsString(chatSessionDTO.getMetadata()) 
                        : null)
                .createdAt(chatSessionDTO.getCreatedAt())
                .updatedAt(chatSessionDTO.getUpdatedAt())
                .build();
    }

    public ChatSessionDTO toDTO(ChatSession chatSession) throws JsonProcessingException {
        Assert.notNull(chatSession, "ChatSession cannot be null");

        return ChatSessionDTO.builder()
                .id(chatSession.getId())
                .agentId(chatSession.getAgentId())
                .title(chatSession.getTitle())
                .metadata(chatSession.getMetadata() != null 
                        ? objectMapper.readValue(chatSession.getMetadata(), ChatSessionDTO.MetaData.class) 
                        : null)
                .createdAt(chatSession.getCreatedAt())
                .updatedAt(chatSession.getUpdatedAt())
                .build();
    }

    public ChatSessionVO toVO(ChatSessionDTO dto) {
        return ChatSessionVO.builder()
                .id(dto.getId())
                .agentId(dto.getAgentId())
                .title(dto.getTitle())
                .mode(dto.getMetadata() != null ? dto.getMetadata().getMode() : null)
                .build();
    }

    public ChatSessionVO toVO(ChatSession chatSession) throws JsonProcessingException {
        return toVO(toDTO(chatSession));
    }

    public ChatSessionDTO toDTO(CreateChatSessionRequest request) {
        Assert.notNull(request, "CreateChatSessionRequest cannot be null");
        Assert.notNull(request.getAgentId(), "AgentId cannot be null");

        ChatSessionDTO.MetaData metadata = ChatSessionDTO.MetaData.builder()
                .mode(request.getMode() != null ? request.getMode() : "SINGLE")
                .build();

        return ChatSessionDTO.builder()
                .agentId(request.getAgentId())
                .title(request.getTitle())
                .metadata(metadata)
                .build();
    }

    public void updateDTOFromRequest(ChatSessionDTO dto, UpdateChatSessionRequest request) {
        Assert.notNull(dto, "ChatSessionDTO cannot be null");
        Assert.notNull(request, "UpdateChatSessionRequest cannot be null");

        if (request.getTitle() != null) {
            dto.setTitle(request.getTitle());
        }
    }

    public ChatSessionDTO.MetaData deserializeMetadata(String metadataJson) throws JsonProcessingException {
        if (metadataJson == null) {
            return null;
        }
        return objectMapper.readValue(metadataJson, ChatSessionDTO.MetaData.class);
    }
}
