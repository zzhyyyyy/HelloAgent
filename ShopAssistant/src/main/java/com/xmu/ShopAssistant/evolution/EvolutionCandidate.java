package com.xmu.ShopAssistant.evolution;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class EvolutionCandidate {
    private String id;
    private EvolutionArtifactType type;
    private String targetName;
    private String baseVersion;
    private String candidateVersion;
    private String content;
    private String rationale;
    private List<String> sourceTraceIds;
    private EvolutionCandidateStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private EvolutionEvaluation evaluation;
    /** 激活本候选前处于 ACTIVE 的候选 ID；为空表示上一版是基线制品。 */
    private String previousCandidateId;
    /** 激活本候选前实际生效的完整内容，用于无外部依赖地精确回滚。 */
    private String previousContent;
}
