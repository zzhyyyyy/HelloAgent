package com.xmu.ShopAssistant.evolution;

import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class EvolutionTrace {
    private String id;
    private String messageId;
    private String sessionId;
    private Instant createdAt;
    private String question;
    private String answer;
    private String promptVersion;
    private List<String> toolsUsed;
    private List<String> skillsUsed;
    private ChatMessageDTO.Usage usage;
    private ChatMessageDTO.EvalScore evalScore;
    private double averageScore;
    private boolean lowScore;
}
