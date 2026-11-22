package com.xmu.ShopAssistant.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MemoryRetrievalServiceTest {

    private MemoryRepresentationMapper representationMapper;
    private MemoryNoteMapper noteMapper;
    private MemoryRetrievalServiceImpl service;

    @BeforeEach
    void setUp() {
        representationMapper = mock(MemoryRepresentationMapper.class);
        noteMapper = mock(MemoryNoteMapper.class);
        service = new MemoryRetrievalServiceImpl(representationMapper, noteMapper);
    }

    @Test
    void shouldFusePageAndNoteRankingsAndRespectLimit() {
        when(representationMapper.searchActive("tenant", "user-a", "agent", "键盘", 12))
                .thenReturn(List.of(
                        page("page-1", "键盘购买记录", "最终选择Keychron Q1"),
                        page("page-2", "外设索引", "键盘与鼠标索引")
                ));
        when(noteMapper.searchActive("tenant", "user-a", "agent", "键盘", 12))
                .thenReturn(List.of(
                        note("note-1", "preference.keyboard", "用户喜欢机械键盘"),
                        note("note-2", "budget.keyboard", "预算1000元")
                ));

        List<MemorySearchResult> results = service.search("tenant", "user-a", "agent", "键盘", 3);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).sourceId()).isEqualTo("page-1");
        assertThat(results).extracting(MemorySearchResult::sourceType).contains("PAGE", "NOTE");
    }

    @Test
    void shouldReadPageWithinOwnerBoundary() {
        MemoryRepresentation page = page("page-1", "A的页面", "A的内容");
        when(representationMapper.selectActiveById("tenant", "user-a", "page-1")).thenReturn(page);

        assertThat(service.readPage("tenant", "user-a", "page-1")).isSameAs(page);
        verify(representationMapper).selectActiveById("tenant", "user-a", "page-1");
        verifyNoMoreInteractions(representationMapper);
    }

    private MemoryRepresentation page(String id, String title, String summary) {
        return MemoryRepresentation.builder().id(id).title(title).summary(summary)
                .body(summary).status(MemoryRepresentationStatus.ACTIVE).build();
    }

    private MemoryNote note(String id, String subject, String content) {
        return MemoryNote.builder().id(id).subject(subject).content(content)
                .status(MemoryNoteStatus.ACTIVE).build();
    }
}
