package com.xmu.ShopAssistant.evolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xmu.ShopAssistant.config.ChatClientRegistry;
import com.xmu.ShopAssistant.prompt.PromptRegistry;
import lombok.Data;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LlmEvolutionCandidateGenerator implements EvolutionCandidateGenerator {

    private final ChatClientRegistry chatClientRegistry;
    private final PromptRegistry promptRegistry;
    private final EvolutionProperties properties;
    private final ObjectMapper objectMapper;

    public LlmEvolutionCandidateGenerator(ChatClientRegistry chatClientRegistry,
                                          PromptRegistry promptRegistry,
                                          EvolutionProperties properties,
                                          ObjectMapper objectMapper) {
        this.chatClientRegistry = chatClientRegistry;
        this.promptRegistry = promptRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public Proposal generate(EvolutionArtifactType type, String targetName, String baseContent,
                             List<EvolutionTrace> traces) {
        ChatClient client = chatClientRegistry.get(properties.getCandidateModel());
        if (client == null) {
            throw new IllegalStateException("未找到候选生成模型: " + properties.getCandidateModel());
        }
        BeanOutputConverter<CandidateOutput> converter = new BeanOutputConverter<>(CandidateOutput.class);
        String prompt = promptRegistry.render("evolution.candidate.v1", Map.of(
                "artifactType", type.name(),
                "targetName", targetName,
                "baseContent", baseContent,
                "lowScoreTraces", toJson(traces),
                "format", converter.getFormat()
        ));
        CandidateOutput output = converter.convert(client.prompt().user(prompt).call().content());
        if (output == null || output.getContent() == null || output.getContent().isBlank()) {
            throw new IllegalStateException("LLM 未生成有效候选内容");
        }
        return new Proposal(output.getContent().trim(), output.getRationale());
    }

    private String toJson(List<EvolutionTrace> traces) {
        try {
            return objectMapper.writeValueAsString(traces);
        } catch (Exception e) {
            throw new IllegalStateException("序列化低分轨迹失败", e);
        }
    }

    @Data
    public static class CandidateOutput {
        private String content;
        private String rationale;
    }
}
