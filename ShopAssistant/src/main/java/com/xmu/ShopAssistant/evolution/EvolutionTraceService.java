package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.guardrails.GuardrailsService;
import com.xmu.ShopAssistant.model.dto.ChatMessageDTO;
import com.xmu.ShopAssistant.model.response.GetChatMessagesResponse;
import com.xmu.ShopAssistant.model.vo.ChatMessageVO;
import com.xmu.ShopAssistant.service.ChatMessageFacadeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class EvolutionTraceService {

    private final EvolutionProperties properties;
    private final EvolutionTraceStore traceStore;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final GuardrailsService guardrailsService;
    private final ObjectMapper objectMapper;

    public EvolutionTraceService(EvolutionProperties properties,
                                 EvolutionTraceStore traceStore,
                                 ChatMessageFacadeService chatMessageFacadeService,
                                 GuardrailsService guardrailsService,
                                 ObjectMapper objectMapper) {
        this.properties = properties;
        this.traceStore = traceStore;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.guardrailsService = guardrailsService;
        this.objectMapper = objectMapper;
    }

    public EvolutionTrace capture(String messageId,
                                  String sessionId,
                                  String question,
                                  String answer,
                                  ChatMessageDTO.Usage usage,
                                  ChatMessageDTO.EvalScore evalScore) {
        if (!properties.isEnabled() || evalScore == null) {
            return null;
        }

        ToolUsage toolUsage = collectToolUsage(sessionId, messageId);
        double average = average(evalScore);
        EvolutionTrace trace = EvolutionTrace.builder()
                .messageId(messageId)
                .sessionId(sessionId)
                .question(guardrailsService.scrubOutput(question).getText())
                .answer(guardrailsService.scrubOutput(answer).getText())
                .promptVersion(properties.getPromptVersion())
                .toolsUsed(new ArrayList<>(toolUsage.tools()))
                .skillsUsed(new ArrayList<>(toolUsage.skills()))
                .usage(usage)
                .evalScore(evalScore)
                .averageScore(average)
                .lowScore(average <= properties.getLowScoreThreshold())
                .build();
        EvolutionTrace saved = traceStore.save(trace);
        log.info("[EvolutionTrace] id={} messageId={} avg={} lowScore={} tools={} skills={}",
                saved.getId(), messageId, average, saved.isLowScore(),
                saved.getToolsUsed(), saved.getSkillsUsed());
        return saved;
    }

    public List<EvolutionTrace> list(boolean lowScoreOnly, int requestedLimit) {
        int limit = Math.min(Math.max(1, requestedLimit), properties.getMaxQueryLimit());
        return traceStore.list(lowScoreOnly, limit);
    }

    private ToolUsage collectToolUsage(String sessionId, String targetMessageId) {
        Set<String> tools = new LinkedHashSet<>();
        Set<String> skills = new LinkedHashSet<>();
        try {
            GetChatMessagesResponse response = chatMessageFacadeService.getChatMessagesBySessionId(sessionId);
            ChatMessageVO[] messages = response == null ? null : response.getChatMessages();
            if (messages == null) return new ToolUsage(tools, skills);

            int targetIndex = indexOf(messages, targetMessageId);
            int startIndex = previousUserIndex(messages, targetIndex);
            for (int i = Math.max(0, startIndex + 1); i >= 0 && i < targetIndex; i++) {
                ChatMessageDTO.MetaData metadata = messages[i].getMetadata();
                if (metadata == null || metadata.getToolCalls() == null) continue;
                metadata.getToolCalls().forEach(call -> {
                    if (call.name() != null && !call.name().isBlank()) {
                        tools.add(call.name());
                    }
                    if ("useSkill".equals(call.name())) {
                        String skill = extractSkillName(call.arguments());
                        if (skill != null) skills.add(skill);
                    }
                });
            }
        } catch (Exception e) {
            log.debug("采集工具轨迹失败 sessionId={} err={}", sessionId, e.getMessage());
        }
        return new ToolUsage(tools, skills);
    }

    private String extractSkillName(String arguments) {
        if (arguments == null || arguments.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(arguments);
            JsonNode value = node.get("skillName");
            return value == null || value.asText().isBlank() ? null : value.asText();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int indexOf(ChatMessageVO[] messages, String messageId) {
        for (int i = 0; i < messages.length; i++) {
            if (messageId.equals(messages[i].getId())) return i;
        }
        return messages.length;
    }

    private static int previousUserIndex(ChatMessageVO[] messages, int targetIndex) {
        for (int i = Math.min(targetIndex - 1, messages.length - 1); i >= 0; i--) {
            if (messages[i].getRole() == ChatMessageDTO.RoleType.USER) return i;
        }
        return -1;
    }

    private static double average(ChatMessageDTO.EvalScore score) {
        int correctness = score.getCorrectness() == null ? 0 : score.getCorrectness();
        int completeness = score.getCompleteness() == null ? 0 : score.getCompleteness();
        int relevance = score.getRelevance() == null ? 0 : score.getRelevance();
        return (correctness + completeness + relevance) / 3.0;
    }

    private record ToolUsage(Set<String> tools, Set<String> skills) {
    }
}
