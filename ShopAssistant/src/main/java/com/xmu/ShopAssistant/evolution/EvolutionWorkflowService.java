package com.xmu.ShopAssistant.evolution;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvolutionWorkflowService {

    private final EvolutionCandidateStore candidateStore;
    private final EvolutionTraceStore traceStore;
    private final EvolutionReplayEvaluator replayEvaluator;
    private final EvolutionArtifactSafetyValidator safetyValidator;
    private final EvolutionArtifactManager artifactManager;

    public EvolutionWorkflowService(EvolutionCandidateStore candidateStore,
                                    EvolutionTraceStore traceStore,
                                    EvolutionReplayEvaluator replayEvaluator,
                                    EvolutionArtifactSafetyValidator safetyValidator,
                                    EvolutionArtifactManager artifactManager) {
        this.candidateStore = candidateStore;
        this.traceStore = traceStore;
        this.replayEvaluator = replayEvaluator;
        this.safetyValidator = safetyValidator;
        this.artifactManager = artifactManager;
    }

    public synchronized EvolutionCandidate evaluate(String id) {
        EvolutionCandidate candidate = require(id);
        requireStatus(candidate, EvolutionCandidateStatus.DRAFT, EvolutionCandidateStatus.EVALUATED);
        safetyValidator.validate(candidate);
        List<EvolutionTrace> traces = sourceTraces(candidate);
        candidate.setStatus(EvolutionCandidateStatus.EVALUATING);
        candidateStore.save(candidate);
        try {
            candidate.setEvaluation(replayEvaluator.evaluate(candidate, traces));
            candidate.setStatus(EvolutionCandidateStatus.EVALUATED);
            return candidateStore.save(candidate);
        } catch (RuntimeException e) {
            candidate.setStatus(EvolutionCandidateStatus.DRAFT);
            candidateStore.save(candidate);
            throw e;
        }
    }

    public synchronized EvolutionCandidate approve(String id) {
        EvolutionCandidate candidate = require(id);
        requireStatus(candidate, EvolutionCandidateStatus.EVALUATED);
        if (candidate.getEvaluation() == null || !candidate.getEvaluation().isPassed()) {
            throw new IllegalStateException("候选未通过回放评估，不能审批");
        }
        candidate.setStatus(EvolutionCandidateStatus.APPROVED);
        return candidateStore.save(candidate);
    }

    public synchronized EvolutionCandidate activate(String id) {
        EvolutionCandidate candidate = require(id);
        requireStatus(candidate, EvolutionCandidateStatus.APPROVED);
        boolean hasActiveVersion = candidateStore.list(Integer.MAX_VALUE).stream()
                .anyMatch(other -> !other.getId().equals(candidate.getId())
                        && other.getStatus() == EvolutionCandidateStatus.ACTIVE
                        && other.getType() == candidate.getType()
                        && other.getTargetName().equals(candidate.getTargetName()));
        if (hasActiveVersion) {
            throw new IllegalStateException("同一目标已有活动候选，请先回滚后再发布新版本");
        }
        safetyValidator.validate(candidate);
        candidate.setPreviousContent(artifactManager.currentContent(candidate.getType(), candidate.getTargetName()));
        artifactManager.activate(candidate);
        candidate.setStatus(EvolutionCandidateStatus.ACTIVE);
        return candidateStore.save(candidate);
    }

    public synchronized EvolutionCandidate rollback(String id) {
        EvolutionCandidate candidate = require(id);
        requireStatus(candidate, EvolutionCandidateStatus.ACTIVE);
        artifactManager.restore(candidate);
        candidate.setStatus(EvolutionCandidateStatus.ROLLED_BACK);
        return candidateStore.save(candidate);
    }

    private EvolutionCandidate require(String id) {
        EvolutionCandidate candidate = candidateStore.find(id);
        if (candidate == null) throw new IllegalArgumentException("未找到演化候选版本: " + id);
        return candidate;
    }

    private List<EvolutionTrace> sourceTraces(EvolutionCandidate candidate) {
        List<EvolutionTrace> traces = new ArrayList<>();
        for (String traceId : candidate.getSourceTraceIds()) {
            EvolutionTrace trace = traceStore.find(traceId);
            if (trace == null) throw new IllegalStateException("候选关联的轨迹已不存在: " + traceId);
            traces.add(trace);
        }
        if (traces.isEmpty()) throw new IllegalStateException("候选没有关联回放样本");
        return traces;
    }

    private static void requireStatus(EvolutionCandidate candidate, EvolutionCandidateStatus... allowed) {
        for (EvolutionCandidateStatus status : allowed) {
            if (candidate.getStatus() == status) return;
        }
        throw new IllegalStateException("当前状态不允许执行该操作: " + candidate.getStatus());
    }
}
