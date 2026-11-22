package com.xmu.ShopAssistant.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryDreamServiceTest {

    private MemoryDreamMapper dreamMapper;
    private MemorySnapshotMapper snapshotMapper;
    private MemoryRepresentationMapper representationMapper;
    private MemoryNoteService noteService;
    private MemoryEventService eventService;
    private MemoryDreamPlanner planner;
    private ApplicationEventPublisher publisher;
    private MemoryDreamServiceImpl service;

    @BeforeEach
    void setUp() {
        dreamMapper = mock(MemoryDreamMapper.class);
        snapshotMapper = mock(MemorySnapshotMapper.class);
        representationMapper = mock(MemoryRepresentationMapper.class);
        noteService = mock(MemoryNoteService.class);
        eventService = mock(MemoryEventService.class);
        planner = mock(MemoryDreamPlanner.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new MemoryDreamServiceImpl(
                dreamMapper, snapshotMapper, representationMapper, noteService,
                eventService, planner, publisher, new ObjectMapper());
    }

    @Test
    void submitShouldPersistPendingJobAndPublishAsyncRequest() {
        MemorySnapshot active = snapshot("snapshot-2", 2, MemorySnapshotStatus.ACTIVE, "snapshot-1");
        when(snapshotMapper.selectActive("tenant", "user-a", "agent-a")).thenReturn(active);

        String jobId = service.submit("tenant", "user-a", "agent-a", "重点整理购物偏好");

        ArgumentCaptor<MemoryDreamJob> jobCaptor = ArgumentCaptor.forClass(MemoryDreamJob.class);
        verify(dreamMapper).insertJob(jobCaptor.capture());
        assertThat(jobCaptor.getValue().getId()).isEqualTo(jobId);
        assertThat(jobCaptor.getValue().getStatus()).isEqualTo(MemoryDreamStatus.PENDING);
        assertThat(jobCaptor.getValue().getInputSnapshotId()).isEqualTo("snapshot-2");
        verify(publisher).publishEvent(new MemoryDreamRequested(jobId));
    }

    @Test
    void executeShouldCreateSeparateCandidateWithoutMutatingActiveSnapshot() {
        MemoryDreamJob job = MemoryDreamJob.builder()
                .id("job-1").tenantId("tenant").ownerId("user-a").agentId("agent-a")
                .status(MemoryDreamStatus.PENDING).build();
        MemorySnapshot active = snapshot("snapshot-2", 2, MemorySnapshotStatus.ACTIVE, "snapshot-1");
        MemoryNote note = MemoryNote.builder().id("note-1").memoryType(MemoryNoteType.PREFERENCE)
                .content("我喜欢机械键盘").status(MemoryNoteStatus.ACTIVE).build();
        DreamRepresentationDraft draft = new DreamRepresentationDraft(
                MemoryRepresentationType.USER_PROFILE, "user-profile", "用户长期画像",
                "摘要", "# 用户长期画像\n- 我喜欢机械键盘", List.of("note-1"), "合并稳定偏好");

        when(dreamMapper.selectJob("job-1")).thenReturn(job);
        when(snapshotMapper.selectActive("tenant", "user-a", "agent-a")).thenReturn(active);
        when(eventService.findByOwner("tenant", "user-a", 1000)).thenReturn(List.of());
        when(noteService.activeNotes("tenant", "user-a", "agent-a", 100)).thenReturn(List.of(note));
        when(planner.plan(anyList(), anyList(), any())).thenReturn(
                new MemoryDreamPlan("整理1条稳定记忆", List.of(draft)));

        MemorySnapshot candidate = service.execute("job-1");

        assertThat(candidate.getStatus()).isEqualTo(MemorySnapshotStatus.CANDIDATE);
        assertThat(candidate.getVersion()).isEqualTo(3);
        assertThat(candidate.getPreviousSnapshotId()).isEqualTo("snapshot-2");
        verify(snapshotMapper).insert(candidate);
        verify(snapshotMapper, never()).updateStatus("snapshot-2", MemorySnapshotStatus.SUPERSEDED);

        ArgumentCaptor<MemoryRepresentation> representationCaptor = ArgumentCaptor.forClass(MemoryRepresentation.class);
        verify(representationMapper).insert(representationCaptor.capture());
        assertThat(representationCaptor.getValue().getStatus()).isEqualTo(MemoryRepresentationStatus.DRAFT);
        assertThat(representationCaptor.getValue().getSnapshotId()).isEqualTo(candidate.getId());

        ArgumentCaptor<MemoryChangeProposal> proposalCaptor = ArgumentCaptor.forClass(MemoryChangeProposal.class);
        verify(dreamMapper).insertProposal(proposalCaptor.capture());
        assertThat(proposalCaptor.getValue().getStatus()).isEqualTo(MemoryProposalStatus.PENDING);
        assertThat(proposalCaptor.getValue().getEvidence()).contains("note-1");
    }

    @Test
    void activateShouldRequireAllProposalsApproved() {
        MemorySnapshot candidate = snapshot("snapshot-3", 3, MemorySnapshotStatus.CANDIDATE, "snapshot-2");
        when(snapshotMapper.selectById("snapshot-3")).thenReturn(candidate);
        when(dreamMapper.selectProposals("snapshot-3")).thenReturn(List.of(
                MemoryChangeProposal.builder().status(MemoryProposalStatus.PENDING).build()));

        assertThatThrownBy(() -> service.activate("snapshot-3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未批准");
        verify(snapshotMapper, never()).updateStatus(any(), any());
    }

    @Test
    void activateShouldAtomicallySwitchActiveSnapshotAfterApproval() {
        MemorySnapshot candidate = snapshot("snapshot-3", 3, MemorySnapshotStatus.CANDIDATE, "snapshot-2");
        MemorySnapshot active = snapshot("snapshot-2", 2, MemorySnapshotStatus.ACTIVE, "snapshot-1");
        when(snapshotMapper.selectById("snapshot-3")).thenReturn(candidate);
        when(dreamMapper.selectProposals("snapshot-3")).thenReturn(List.of(
                MemoryChangeProposal.builder().status(MemoryProposalStatus.APPROVED).build()));
        when(snapshotMapper.selectActive("tenant", "user-a", "agent-a")).thenReturn(active);

        service.activate("snapshot-3");

        verify(snapshotMapper).updateStatus("snapshot-2", MemorySnapshotStatus.SUPERSEDED);
        verify(snapshotMapper).updateStatus("snapshot-3", MemorySnapshotStatus.ACTIVE);
        verify(representationMapper).updateStatusBySnapshot("snapshot-3", MemoryRepresentationStatus.ACTIVE);
    }

    @Test
    void rollbackShouldRestorePreviousSnapshotAndRepresentations() {
        MemorySnapshot active = snapshot("snapshot-3", 3, MemorySnapshotStatus.ACTIVE, "snapshot-2");
        MemorySnapshot previous = snapshot("snapshot-2", 2, MemorySnapshotStatus.SUPERSEDED, "snapshot-1");
        when(snapshotMapper.selectById("snapshot-3")).thenReturn(active);
        when(snapshotMapper.selectById("snapshot-2")).thenReturn(previous);

        MemorySnapshot restored = service.rollback("snapshot-3");

        assertThat(restored).isSameAs(previous);
        verify(snapshotMapper).updateStatus("snapshot-3", MemorySnapshotStatus.ROLLED_BACK);
        verify(representationMapper).updateStatusBySnapshot("snapshot-3", MemoryRepresentationStatus.SUPERSEDED);
        verify(snapshotMapper).updateStatus("snapshot-2", MemorySnapshotStatus.ACTIVE);
        verify(representationMapper).updateStatusBySnapshot("snapshot-2", MemoryRepresentationStatus.ACTIVE);
    }

    private MemorySnapshot snapshot(String id, int version, MemorySnapshotStatus status, String previousId) {
        return MemorySnapshot.builder()
                .id(id).tenantId("tenant").ownerId("user-a").agentId("agent-a")
                .version(version).status(status).previousSnapshotId(previousId).build();
    }
}
