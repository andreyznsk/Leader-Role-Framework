package ru.andreyz.memoryservice.search;

import org.junit.jupiter.api.Test;
import ru.andreyz.common.agent.AgentClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalSearchServiceTest {

    private final AgentClient agentClient = mock(AgentClient.class);
    private final SearchPromptBuilder promptBuilder = new SearchPromptBuilder();
    private final SearchQueryParser parser = new SearchQueryParser();

    @Test
    void quickSearch_mergesResultsByScoreAndSkipsFailedProvider() {
        SearchProvider taskProvider = fixedProvider(
                SearchLayer.TASK,
                new SearchResultItem(SearchLayer.TASK, "Task A", "blocked", "/ui/today", "1", "TASK", 0.91, Instant.now())
        );
        SearchProvider noticeProvider = new SearchProvider() {
            @Override
            public SearchLayer layer() {
                return SearchLayer.NOTICE;
            }

            @Override
            public List<SearchResultItem> search(String query, int limit) {
                throw new IllegalStateException("db unavailable");
            }
        };
        SearchProvider riskProvider = fixedProvider(
                SearchLayer.RISK,
                new SearchResultItem(SearchLayer.RISK, "Risk B", "payment release", "/ui/risks", "2", "RISK", 0.74, Instant.now())
        );

        GlobalSearchService service = new GlobalSearchService(
                List.of(riskProvider, noticeProvider, taskProvider),
                promptBuilder,
                agentClient,
                parser
        );

        SearchResponse response = service.search(new SearchRequest(
                "что зависло по релизу платежей",
                List.of(SearchLayer.TASK, SearchLayer.NOTICE, SearchLayer.RISK),
                SearchMode.QUICK,
                5
        ));

        assertThat(response.summary()).isNull();
        assertThat(response.results()).extracting(SearchResultItem::title)
                .containsExactly("Task A", "Risk B");
        verify(agentClient, never()).complete(anyString());
    }

    @Test
    void deepSearch_buildsSummaryFromMergedResults() {
        SearchProvider taskProvider = fixedProvider(
                SearchLayer.TASK,
                new SearchResultItem(SearchLayer.TASK, "Prepare release", "Blocked by QA", "/ui/today", "7", "TASK", 0.88, Instant.now())
        );

        when(agentClient.complete(anyString())).thenReturn("Open the task first.");

        GlobalSearchService service = new GlobalSearchService(
                List.of(taskProvider),
                promptBuilder,
                agentClient,
                parser
        );

        SearchResponse response = service.search(new SearchRequest(
                "release blocker",
                List.of(SearchLayer.TASK),
                SearchMode.DEEP,
                3
        ));

        assertThat(response.summary()).isEqualTo("Open the task first.");
        assertThat(response.results()).hasSize(1);
        verify(agentClient).complete(anyString());
    }

    private static SearchProvider fixedProvider(SearchLayer layer, SearchResultItem... items) {
        return new SearchProvider() {
            @Override
            public SearchLayer layer() {
                return layer;
            }

            @Override
            public List<SearchResultItem> search(String query, int limit) {
                return List.of(items);
            }
        };
    }
}
