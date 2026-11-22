package com.xmu.ShopAssistant.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MemoryRetrievalServiceImpl implements MemoryRetrievalService {

    private static final int RRF_K = 60;
    private static final int MAX_LIMIT = 20;

    private final MemoryRepresentationMapper representationMapper;
    private final MemoryNoteMapper noteMapper;

    @Override
    public List<MemorySearchResult> search(
            String tenantId, String ownerId, String agentId, String query, int limit) {
        requireScope(tenantId, ownerId);
        if (!StringUtils.hasText(query)) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        int candidateLimit = Math.min(MAX_LIMIT * 4, Math.max(safeLimit * 4, 10));

        List<MemoryRepresentation> pages = representationMapper.searchActive(
                tenantId, ownerId, agentId, query, candidateLimit);
        List<MemoryNote> notes = noteMapper.searchActive(
                tenantId, ownerId, agentId, query, candidateLimit);

        Map<String, MutableResult> fused = new LinkedHashMap<>();
        for (int i = 0; i < pages.size(); i++) {
            MemoryRepresentation page = pages.get(i);
            String key = "PAGE:" + page.getId();
            fused.computeIfAbsent(key, ignored -> MutableResult.page(page))
                    .score += 1.0 / (RRF_K + i + 1);
        }
        for (int i = 0; i < notes.size(); i++) {
            MemoryNote note = notes.get(i);
            String key = "NOTE:" + note.getId();
            fused.computeIfAbsent(key, ignored -> MutableResult.note(note))
                    .score += 1.0 / (RRF_K + i + 1);
        }

        return fused.values().stream()
                .sorted(Comparator.comparingDouble((MutableResult result) -> result.score).reversed())
                .limit(safeLimit)
                .map(MutableResult::toResult)
                .toList();
    }

    @Override
    public MemoryRepresentation readPage(String tenantId, String ownerId, String pageId) {
        requireScope(tenantId, ownerId);
        if (!StringUtils.hasText(pageId)) throw new IllegalArgumentException("pageId 不能为空");
        return representationMapper.selectActiveById(tenantId, ownerId, pageId);
    }

    private void requireScope(String tenantId, String ownerId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("tenantId 和 ownerId 不能为空");
        }
    }

    private static final class MutableResult {
        private final String sourceType;
        private final String sourceId;
        private final String title;
        private final String content;
        private double score;

        private MutableResult(String sourceType, String sourceId, String title, String content) {
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.title = title;
            this.content = content;
        }

        static MutableResult page(MemoryRepresentation page) {
            String content = StringUtils.hasText(page.getSummary()) ? page.getSummary() : page.getBody();
            return new MutableResult("PAGE", page.getId(), page.getTitle(), content);
        }

        static MutableResult note(MemoryNote note) {
            return new MutableResult("NOTE", note.getId(), note.getSubject(), note.getContent());
        }

        MemorySearchResult toResult() {
            return new MemorySearchResult(sourceType, sourceId, title, content, score);
        }
    }
}
