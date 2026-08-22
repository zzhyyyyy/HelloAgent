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
        safetyValidator.validate(candidate);

        EvolutionCandidate previousActive = findActiveCandidate(candidate);
        candidate.setPreviousContent(artifactManager.currentContent(candidate.getType(), candidate.getTargetName()));
        candidate.setPreviousCandidateId(previousActive == null ? null : previousActive.getId());
        // 先保存版本链元数据；若制品切换失败，候选仍保持 APPROVED，可安全重试。
        candidateStore.save(candidate);

        try {
            artifactManager.activate(candidate);
            if (previousActive != null) {
                previousActive.setStatus(EvolutionCandidateStatus.SUPERSEDED);
                candidateStore.save(previousActive);
            }
            candidate.setStatus(EvolutionCandidateStatus.ACTIVE);
            return candidateStore.save(candidate);
        } catch (RuntimeException activationError) {
            compensateFailedActivation(candidate, previousActive, activationError);
            throw activationError;
        }
    }

    public synchronized EvolutionCandidate rollback(String id) {
        EvolutionCandidate candidate = require(id);
        requireStatus(candidate, EvolutionCandidateStatus.ACTIVE);
        EvolutionCandidate previous = requirePreviousCandidate(candidate);

        try {
            artifactManager.restore(candidate);
            candidate.setStatus(EvolutionCandidateStatus.ROLLED_BACK);
            EvolutionCandidate saved = candidateStore.save(candidate);
            if (previous != null) {
                previous.setStatus(EvolutionCandidateStatus.ACTIVE);
                candidateStore.save(previous);
            }
            return saved;
        } catch (RuntimeException rollbackError) {
            compensateFailedRollback(candidate, previous, rollbackError);
            throw rollbackError;
        }
    }

    private EvolutionCandidate findActiveCandidate(EvolutionCandidate candidate) {
        List<EvolutionCandidate> active = candidateStore.list(Integer.MAX_VALUE).stream()
                .filter(other -> !other.getId().equals(candidate.getId()))
                .filter(other -> other.getStatus() == EvolutionCandidateStatus.ACTIVE)
                .filter(other -> other.getType() == candidate.getType())
                .filter(other -> other.getTargetName().equals(candidate.getTargetName()))
                .toList();
        if (active.size() > 1) {
            throw new IllegalStateException("同一目标存在多个活动候选，拒绝继续发布");
        }
        return active.isEmpty() ? null : active.get(0);
    }

    /** 兼容旧候选：previousCandidateId 为空时，previousContent 仍可回滚到基线内容。 */
    private EvolutionCandidate requirePreviousCandidate(EvolutionCandidate candidate) {
        String previousId = candidate.getPreviousCandidateId();
        if (previousId == null || previousId.isBlank()) return null;
        EvolutionCandidate previous = candidateStore.find(previousId);
        if (previous == null) {
            throw new IllegalStateException("上一候选版本不存在，拒绝破坏当前活动版本: " + previousId);
        }
        if (previous.getStatus() != EvolutionCandidateStatus.SUPERSEDED) {
            throw new IllegalStateException("上一候选版本状态异常: " + previous.getStatus());
        }
        return previous;
    }

    private void compensateFailedActivation(EvolutionCandidate candidate,
                                            EvolutionCandidate previous,
                                            RuntimeException original) {
        try {
            artifactManager.restore(candidate);
            candidate.setStatus(EvolutionCandidateStatus.APPROVED);
            candidateStore.save(candidate);
            if (previous != null) {
                previous.setStatus(EvolutionCandidateStatus.ACTIVE);
                candidateStore.save(previous);
            }
        } catch (RuntimeException compensationError) {
            original.addSuppressed(compensationError);
        }
    }

    private void compensateFailedRollback(EvolutionCandidate candidate,
                                          EvolutionCandidate previous,
                                          RuntimeException original) {
        try {
            artifactManager.activate(candidate);
            candidate.setStatus(EvolutionCandidateStatus.ACTIVE);
            candidateStore.save(candidate);
            if (previous != null) {
                previous.setStatus(EvolutionCandidateStatus.SUPERSEDED);
                candidateStore.save(previous);
            }
        } catch (RuntimeException compensationError) {
            original.addSuppressed(compensationError);
        }
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
