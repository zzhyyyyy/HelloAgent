package com.xmu.ShopAssistant.evolution;

import com.xmu.ShopAssistant.prompt.PromptRegistry;
import com.xmu.ShopAssistant.skill.Skill;
import com.xmu.ShopAssistant.skill.SkillRegistry;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

@Service
public class EvolutionCandidateService {

    private final EvolutionProperties properties;
    private final EvolutionTraceStore traceStore;
    private final EvolutionCandidateStore candidateStore;
    private final EvolutionCandidateGenerator candidateGenerator;
    private final PromptRegistry promptRegistry;
    private final SkillRegistry skillRegistry;

    public EvolutionCandidateService(EvolutionProperties properties,
                                     EvolutionTraceStore traceStore,
                                     EvolutionCandidateStore candidateStore,
                                     EvolutionCandidateGenerator candidateGenerator,
                                     PromptRegistry promptRegistry,
                                     SkillRegistry skillRegistry) {
        this.properties = properties;
        this.traceStore = traceStore;
        this.candidateStore = candidateStore;
        this.candidateGenerator = candidateGenerator;
        this.promptRegistry = promptRegistry;
        this.skillRegistry = skillRegistry;
    }

    public EvolutionCandidate generate(GenerateRequest request) {
        EvolutionArtifactType type = request.getType() == null ? EvolutionArtifactType.PROMPT : request.getType();
        List<EvolutionTrace> traces = resolveLowScoreTraces(request.getTraceIds());
        if (traces.isEmpty()) throw new IllegalArgumentException("没有可用于演化的低分轨迹");

        String targetName = resolveTargetName(type, request.getTargetName(), traces);
        String baseContent = resolveBaseContent(type, targetName);
        EvolutionCandidateGenerator.Proposal proposal =
                candidateGenerator.generate(type, targetName, baseContent, traces);
        String version = targetName + ".candidate." + DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        return candidateStore.save(EvolutionCandidate.builder()
                .type(type)
                .targetName(targetName)
                .baseVersion(targetName)
                .candidateVersion(version)
                .content(proposal.content())
                .rationale(proposal.rationale())
                .sourceTraceIds(traces.stream().map(EvolutionTrace::getId).toList())
                .status(EvolutionCandidateStatus.DRAFT)
                .build());
    }

    public List<EvolutionCandidate> list(int requestedLimit) {
        int limit = Math.min(Math.max(1, requestedLimit), properties.getMaxQueryLimit());
        return candidateStore.list(limit);
    }

    public EvolutionCandidate findRequired(String id) {
        EvolutionCandidate candidate = candidateStore.find(id);
        if (candidate == null) throw new IllegalArgumentException("未找到演化候选版本: " + id);
        return candidate;
    }

    private List<EvolutionTrace> resolveLowScoreTraces(List<String> requestedIds) {
        List<EvolutionTrace> traces = new ArrayList<>();
        if (requestedIds == null || requestedIds.isEmpty()) {
            traces.addAll(traceStore.list(true, properties.getMaxCandidateTraces()));
        } else {
            for (String id : new LinkedHashSet<>(requestedIds)) {
                EvolutionTrace trace = traceStore.find(id);
                if (trace == null) throw new IllegalArgumentException("未找到演化轨迹: " + id);
                if (!trace.isLowScore()) throw new IllegalArgumentException("轨迹不是低分样本: " + id);
                traces.add(trace);
                if (traces.size() >= properties.getMaxCandidateTraces()) break;
            }
        }
        return traces;
    }

    private String resolveTargetName(EvolutionArtifactType type, String requested,
                                     List<EvolutionTrace> traces) {
        if (requested != null && !requested.isBlank()) return validateName(requested);
        if (type == EvolutionArtifactType.PROMPT) return properties.getPromptVersion();
        return traces.stream()
                .flatMap(trace -> trace.getSkillsUsed() == null ? List.<String>of().stream() : trace.getSkillsUsed().stream())
                .findFirst()
                .map(EvolutionCandidateService::validateName)
                .orElseThrow(() -> new IllegalArgumentException("Skill 演化必须指定 targetName，或轨迹中包含 Skill 调用"));
    }

    private String resolveBaseContent(EvolutionArtifactType type, String targetName) {
        if (type == EvolutionArtifactType.PROMPT) return promptRegistry.get(targetName);
        Skill skill = skillRegistry.get(targetName);
        if (skill == null) throw new IllegalArgumentException("未找到 Skill: " + targetName);
        return "---\nname: " + skill.getName() + "\ndescription: " + skill.getDescription()
                + "\nallowedTools: [" + String.join(", ", skill.getAllowedTools()) + "]\n---\n\n"
                + skill.getInstructions();
    }

    private static String validateName(String name) {
        String trimmed = name.trim();
        if (!trimmed.matches("[A-Za-z0-9._-]{1,100}")) {
            throw new IllegalArgumentException("目标名称格式不合法");
        }
        return trimmed;
    }

    @Data
    public static class GenerateRequest {
        private EvolutionArtifactType type;
        private String targetName;
        private List<String> traceIds = new ArrayList<>();
    }
}
