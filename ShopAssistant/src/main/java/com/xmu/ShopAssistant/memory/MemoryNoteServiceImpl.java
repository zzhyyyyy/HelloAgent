package com.xmu.ShopAssistant.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MemoryNoteServiceImpl implements MemoryNoteService {

    private static final double ACTIVE_THRESHOLD = 0.80;
    private static final int MAX_QUERY_LIMIT = 100;

    private final MemoryNoteMapper mapper;
    private final List<MemoryNoteExtractor> extractors;

    @Override
    @Transactional
    public void observe(MemoryEvent event) {
        if (!eligible(event)) return;
        for (MemoryNoteExtractor extractor : extractors) {
            for (ExtractedMemoryNote extracted : extractor.extract(event)) {
                upsert(event, extracted);
            }
        }
    }

    @Override
    public List<MemoryNote> activeNotes(String tenantId, String ownerId, String agentId, int limit) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("tenantId 和 ownerId 不能为空");
        }
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, MAX_QUERY_LIMIT);
        return mapper.selectActiveByOwner(tenantId, ownerId, agentId, safeLimit);
    }

    private void upsert(MemoryEvent event, ExtractedMemoryNote extracted) {
        MemoryNote existing = mapper.selectActiveBySubject(
                event.getTenantId(), event.getOwnerId(), event.getAgentId(),
                extracted.scope(), extracted.subject());
        LocalDateTime now = LocalDateTime.now();

        if (existing != null && sameContent(existing.getContent(), extracted.content())) {
            int evidenceCount = value(existing.getEvidenceCount(), 1) + 1;
            double confidence = Math.min(0.99,
                    Math.max(value(existing.getConfidence(), extracted.confidence()), extracted.confidence()) + 0.02);
            mapper.update(MemoryNote.builder()
                    .id(existing.getId())
                    .confidence(confidence)
                    .evidenceCount(evidenceCount)
                    .updatedAt(now)
                    .build());
            addEvidence(existing.getId(), event, extracted);
            return;
        }

        if (existing != null) {
            mapper.update(MemoryNote.builder()
                    .id(existing.getId())
                    .status(MemoryNoteStatus.SUPERSEDED)
                    .validTo(now)
                    .updatedAt(now)
                    .build());
        }

        MemoryNote note = MemoryNote.builder()
                .id(UUID.randomUUID().toString())
                .tenantId(event.getTenantId())
                .ownerId(event.getOwnerId())
                .agentId(event.getAgentId())
                .scope(extracted.scope())
                .memoryType(extracted.memoryType())
                .subject(extracted.subject())
                .content(extracted.content())
                .structuredContent(extracted.structuredContent())
                .confidence(extracted.confidence())
                .salience(extracted.salience())
                .status(extracted.confidence() >= ACTIVE_THRESHOLD
                        ? MemoryNoteStatus.ACTIVE : MemoryNoteStatus.CANDIDATE)
                .version(existing == null ? 1 : value(existing.getVersion(), 1) + 1)
                .previousNoteId(existing == null ? null : existing.getId())
                .source(extracted.source())
                .evidenceCount(1)
                .validFrom(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        mapper.insert(note);
        addEvidence(note.getId(), event, extracted);
    }

    private void addEvidence(String noteId, MemoryEvent event, ExtractedMemoryNote extracted) {
        mapper.insertEvidence(MemoryNoteEvidence.builder()
                .id(UUID.randomUUID().toString())
                .noteId(noteId)
                .eventId(event.getId())
                .evidenceRole("SUPPORTS")
                .evidenceExcerpt(truncate(extracted.content(), 280))
                .createdAt(LocalDateTime.now())
                .build());
    }

    private boolean eligible(MemoryEvent event) {
        return event != null
                && event.getEventType() == MemoryEventType.USER_MESSAGE
                && event.getTrustLevel() == MemoryTrustLevel.USER_ASSERTED
                && StringUtils.hasText(event.getId())
                && StringUtils.hasText(event.getTenantId())
                && StringUtils.hasText(event.getOwnerId());
    }

    private boolean sameContent(String left, String right) {
        return left != null && left.equals(right);
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private double value(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
