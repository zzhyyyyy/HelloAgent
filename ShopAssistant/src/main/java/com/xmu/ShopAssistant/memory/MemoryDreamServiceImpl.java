package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemoryDreamServiceImpl implements MemoryDreamService {

    private final MemoryDreamMapper dreamMapper;
    private final MemorySnapshotMapper snapshotMapper;
    private final MemoryRepresentationMapper representationMapper;
    private final MemoryNoteService noteService;
    private final MemoryEventService eventService;
    private final MemoryDreamPlanner planner;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper objectMapper;

    @Override
    public String submit(String tenantId, String ownerId, String agentId, String instructions) {
        requireScope(tenantId, ownerId);
        MemorySnapshot active = snapshotMapper.selectActive(tenantId, ownerId, agentId);
        MemoryDreamJob job = MemoryDreamJob.builder()
                .id(uuid())
                .tenantId(tenantId)
                .ownerId(ownerId)
                .agentId(agentId)
                .inputSnapshotId(active == null ? null : active.getId())
                .instructions(instructions)
                .status(MemoryDreamStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();
        dreamMapper.insertJob(job);
        publisher.publishEvent(new MemoryDreamRequested(job.getId()));
        return job.getId();
    }

    @Override
    public MemorySnapshot execute(String jobId) {
        MemoryDreamJob job = dreamMapper.selectJob(jobId);
        if (job == null) throw new IllegalArgumentException("Dream Job 不存在: " + jobId);
        markRunning(job);
        try {
            List<MemoryEvent> events = eventService.findByOwner(job.getTenantId(), job.getOwnerId(), 1000);
            List<MemoryNote> notes = noteService.activeNotes(
                    job.getTenantId(), job.getOwnerId(), job.getAgentId(), 100);
            MemoryDreamPlan plan = planner.plan(events, notes, job.getInstructions());
            MemorySnapshot active = snapshotMapper.selectActive(
                    job.getTenantId(), job.getOwnerId(), job.getAgentId());
            MemorySnapshot candidate = MemorySnapshot.builder()
                    .id(uuid())
                    .tenantId(job.getTenantId())
                    .ownerId(job.getOwnerId())
                    .agentId(job.getAgentId())
                    .version(active == null ? 1 : active.getVersion() + 1)
                    .status(MemorySnapshotStatus.CANDIDATE)
                    .previousSnapshotId(active == null ? null : active.getId())
                    .summary(plan.summary())
                    .createdAt(LocalDateTime.now())
                    .build();
            snapshotMapper.insert(candidate);
            persistCandidate(job, candidate, notes, plan);
            markCompleted(job, candidate, plan.summary());
            return candidate;
        } catch (RuntimeException e) {
            markFailed(job, e);
            throw e;
        }
    }

    @Override
    public void approveProposal(String proposalId) {
        dreamMapper.updateProposalStatus(proposalId, MemoryProposalStatus.APPROVED);
    }

    @Override
    public void rejectProposal(String proposalId) {
        dreamMapper.updateProposalStatus(proposalId, MemoryProposalStatus.REJECTED);
    }

    @Override
    @Transactional
    public void activate(String snapshotId) {
        MemorySnapshot candidate = snapshotMapper.selectById(snapshotId);
        if (candidate == null || candidate.getStatus() != MemorySnapshotStatus.CANDIDATE) {
            throw new IllegalStateException("只能激活 CANDIDATE 快照");
        }
        List<MemoryChangeProposal> proposals = dreamMapper.selectProposals(snapshotId);
        if (proposals.stream().anyMatch(p -> p.getStatus() != MemoryProposalStatus.APPROVED)) {
            throw new IllegalStateException("候选快照仍有未批准的记忆变更");
        }
        MemorySnapshot active = snapshotMapper.selectActive(
                candidate.getTenantId(), candidate.getOwnerId(), candidate.getAgentId());
        if (active != null) snapshotMapper.updateStatus(active.getId(), MemorySnapshotStatus.SUPERSEDED);
        snapshotMapper.updateStatus(candidate.getId(), MemorySnapshotStatus.ACTIVE);
        representationMapper.updateStatusBySnapshot(candidate.getId(), MemoryRepresentationStatus.ACTIVE);
    }

    @Override
    @Transactional
    public MemorySnapshot rollback(String activeSnapshotId) {
        MemorySnapshot active = snapshotMapper.selectById(activeSnapshotId);
        if (active == null || active.getStatus() != MemorySnapshotStatus.ACTIVE) {
            throw new IllegalStateException("只能回滚 ACTIVE 快照");
        }
        if (!StringUtils.hasText(active.getPreviousSnapshotId())) {
            throw new IllegalStateException("当前快照没有可回滚的上一版本");
        }
        MemorySnapshot previous = snapshotMapper.selectById(active.getPreviousSnapshotId());
        if (previous == null) throw new IllegalStateException("上一版本快照不存在");
        snapshotMapper.updateStatus(active.getId(), MemorySnapshotStatus.ROLLED_BACK);
        representationMapper.updateStatusBySnapshot(active.getId(), MemoryRepresentationStatus.SUPERSEDED);
        snapshotMapper.updateStatus(previous.getId(), MemorySnapshotStatus.ACTIVE);
        representationMapper.updateStatusBySnapshot(previous.getId(), MemoryRepresentationStatus.ACTIVE);
        return previous;
    }

    private void persistCandidate(
            MemoryDreamJob job,
            MemorySnapshot candidate,
            List<MemoryNote> notes,
            MemoryDreamPlan plan
    ) {
        for (MemoryNote note : notes) {
            snapshotMapper.insertItem(MemorySnapshotItem.builder()
                    .snapshotId(candidate.getId()).itemType("NOTE").itemId(note.getId()).build());
        }
        for (DreamRepresentationDraft draft : plan.representations()) {
            String representationId = uuid();
            MemoryRepresentation representation = MemoryRepresentation.builder()
                    .id(representationId)
                    .tenantId(candidate.getTenantId())
                    .ownerId(candidate.getOwnerId())
                    .agentId(candidate.getAgentId())
                    .representationType(draft.type())
                    .slug(draft.slug())
                    .title(draft.title())
                    .summary(draft.summary())
                    .body(draft.body())
                    .links(toJson(draft.sourceNoteIds()))
                    .status(MemoryRepresentationStatus.DRAFT)
                    .version(candidate.getVersion())
                    .snapshotId(candidate.getId())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            representationMapper.insert(representation);
            snapshotMapper.insertItem(MemorySnapshotItem.builder()
                    .snapshotId(candidate.getId()).itemType("REPRESENTATION")
                    .itemId(representationId).build());
            dreamMapper.insertProposal(MemoryChangeProposal.builder()
                    .id(uuid()).dreamJobId(job.getId()).snapshotId(candidate.getId())
                    .changeType("UPSERT").targetType("REPRESENTATION").targetId(representationId)
                    .rationale(draft.rationale()).evidence(toJson(draft.sourceNoteIds()))
                    .status(MemoryProposalStatus.PENDING).createdAt(LocalDateTime.now()).build());
        }
    }

    private void markRunning(MemoryDreamJob job) {
        dreamMapper.updateJob(MemoryDreamJob.builder().id(job.getId())
                .status(MemoryDreamStatus.RUNNING).startedAt(LocalDateTime.now()).build());
    }

    private void markCompleted(MemoryDreamJob job, MemorySnapshot candidate, String summary) {
        dreamMapper.updateJob(MemoryDreamJob.builder().id(job.getId())
                .status(MemoryDreamStatus.COMPLETED).outputSnapshotId(candidate.getId())
                .summary(summary).completedAt(LocalDateTime.now()).build());
    }

    private void markFailed(MemoryDreamJob job, RuntimeException error) {
        dreamMapper.updateJob(MemoryDreamJob.builder().id(job.getId())
                .status(MemoryDreamStatus.FAILED).errorMessage(error.getMessage())
                .completedAt(LocalDateTime.now()).build());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Dreaming 证据失败", e);
        }
    }

    private void requireScope(String tenantId, String ownerId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("tenantId 和 ownerId 不能为空");
        }
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }
}
