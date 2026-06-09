package ru.andreyz.ragservice.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagSearchServiceTest {

    @Mock
    OllamaClient ollama;
    @Mock
    OpenSearchClient openSearch;

    RagSearchService service;

    @BeforeEach
    void setUp() {
        service = new RagSearchService(ollama, openSearch);
    }

    @Test
    void search_embedsQueryBeforeCallingKnn() {
        float[] queryVector = new float[]{0.1f, 0.2f, 0.3f};
        when(ollama.embed("релиз процесс")).thenReturn(queryVector);
        when(openSearch.knnSearch(any(), anyInt())).thenReturn(List.of());

        service.search("релиз процесс", 5);

        ArgumentCaptor<float[]> vectorCaptor = ArgumentCaptor.forClass(float[].class);
        verify(openSearch).knnSearch(vectorCaptor.capture(), eq(5));
        assertThat(vectorCaptor.getValue()).isEqualTo(queryVector);
    }

    @Test
    void search_passesTopKToOpenSearch() {
        when(ollama.embed(any())).thenReturn(new float[1024]);
        when(openSearch.knnSearch(any(), anyInt())).thenReturn(List.of());

        service.search("any query", 10);

        verify(openSearch).knnSearch(any(), eq(10));
    }

    @Test
    void search_returnsResultsFromOpenSearch() {
        SearchResult expected = new SearchResult("Postgres ACID.", "rag-inbox/adr.md", 0.92, 0);
        when(ollama.embed(any())).thenReturn(new float[1024]);
        when(openSearch.knnSearch(any(), anyInt())).thenReturn(List.of(expected));

        List<SearchResult> results = service.search("database", 5);

        assertThat(results).containsExactly(expected);
    }

    @Test
    void search_emptyResults_returnsEmptyList() {
        when(ollama.embed(any())).thenReturn(new float[1024]);
        when(openSearch.knnSearch(any(), anyInt())).thenReturn(List.of());

        List<SearchResult> results = service.search("unknown topic", 5);

        assertThat(results).isEmpty();
    }

    @Test
    void search_multipleResults_preservesOrder() {
        SearchResult r1 = new SearchResult("High score.", "file1.md", 0.95, 0);
        SearchResult r2 = new SearchResult("Mid score.", "file2.md", 0.80, 1);
        SearchResult r3 = new SearchResult("Low score.", "file3.md", 0.60, 0);
        when(ollama.embed(any())).thenReturn(new float[1024]);
        when(openSearch.knnSearch(any(), anyInt())).thenReturn(List.of(r1, r2, r3));

        List<SearchResult> results = service.search("query", 3);

        assertThat(results).containsExactly(r1, r2, r3);
    }
}
