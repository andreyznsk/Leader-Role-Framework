package ru.andreyz.memoryservice.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.common.agent.AgentException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class GlobalSearchService {

    private static final Logger log = LoggerFactory.getLogger(GlobalSearchService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final long QUICK_TIMEOUT_MS = 1500;
    private static final long DEEP_TIMEOUT_MS = 5000;
    private static final Set<SearchLayer> AVAILABLE_LAYERS = EnumSet.of(
            SearchLayer.NOTICE, SearchLayer.TASK, SearchLayer.PEOPLE,
            SearchLayer.RISK, SearchLayer.INCIDENT, SearchLayer.KNOWLEDGE
    );

    private final List<SearchProvider> providers;
    private final SearchPromptBuilder promptBuilder;
    private final AgentClient agentClient;
    private final SearchQueryParser searchQueryParser;

    public GlobalSearchService(List<SearchProvider> providers,
                               SearchPromptBuilder promptBuilder,
                               AgentClient agentClient,
                               SearchQueryParser searchQueryParser) {
        this.providers = providers;
        this.promptBuilder = promptBuilder;
        this.agentClient = agentClient;
        this.searchQueryParser = searchQueryParser;
    }

    public SearchResponse search(SearchRequest request) {
        List<SearchLayer> layers = normalizeLayers(request.layers());
        int limit = normalizeLimit(request.limit());
        ParsedSearchQuery parsedQuery = searchQueryParser.parse(request.query());
        long timeoutMs = request.mode() == SearchMode.DEEP ? DEEP_TIMEOUT_MS : QUICK_TIMEOUT_MS;

        List<CompletableFuture<List<SearchResultItem>>> futures = providers.stream()
                .filter(p -> layers.contains(p.layer()))
                .map(provider -> CompletableFuture.supplyAsync(() -> provider.search(parsedQuery.originalQuery(), limit))
                        .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                        .exceptionally(error -> {
                            log.warn("Provider {} failed: {}", provider.layer(), error.getMessage());
                            return List.of();
                        }))
                .toList();

        List<SearchResultItem> results = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .sorted(Comparator.comparing(SearchResultItem::score).reversed())
                .limit(limit)
                .toList();

        String summary = null;
        if (request.mode() == SearchMode.DEEP && !results.isEmpty()) {
            try {
                String prompt = promptBuilder.build(request.query(), results);
                summary = agentClient.complete(prompt);
            } catch (AgentException e) {
                log.warn("Deep search AI summary failed: {}", e.getMessage());
            }
        }

        return new SearchResponse(request.query(), request.mode(), layers, summary, results);
    }

    public List<LayerInfo> getLayers() {
        return List.of(
                new LayerInfo("NOTICE", "Notice", true, true),
                new LayerInfo("TASK", "Tasks", true, true),
                new LayerInfo("PEOPLE", "People", true, true),
                new LayerInfo("RISK", "Risks", true, true),
                new LayerInfo("INCIDENT", "Incidents", true, true),
                new LayerInfo("KNOWLEDGE", "Knowledge / RAG", true, true),
                new LayerInfo("MAIL", "Mail", false, false),
                new LayerInfo("CALENDAR", "Calendar", false, false)
        );
    }

    private List<SearchLayer> normalizeLayers(List<SearchLayer> layers) {
        if (layers == null || layers.isEmpty()) {
            return List.copyOf(AVAILABLE_LAYERS);
        }
        return layers.stream().filter(AVAILABLE_LAYERS::contains).toList();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, 100);
    }
}
