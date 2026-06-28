package ru.andreyz.memoryservice.search.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;
import ru.andreyz.memoryservice.service.KnowledgeService;

import java.util.ArrayList;
import java.util.List;

/**
 * Searches Notice documents via the existing KnowledgeService proxy to JavaRagService.
 * Notices are stored in RAG (not in local PostgreSQL), so this uses a listing + client-side match.
 */
@Component
public class NoticeSearchProvider implements SearchProvider {

    private static final Logger log = LoggerFactory.getLogger(NoticeSearchProvider.class);

    private final KnowledgeService knowledgeService;

    public NoticeSearchProvider(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.NOTICE;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        String q = query.toLowerCase();
        var results = new ArrayList<SearchResultItem>();

        List<KnowledgeService.KnowledgeDocumentSummary> notices;
        try {
            notices = knowledgeService.list("NOTICE");
        } catch (Exception e) {
            log.warn("Notice search unavailable: {}", e.getMessage());
            return List.of();
        }

        for (var doc : notices) {
            double score = scoreNotice(q, doc);
            if (score > 0) {
                String snippet = doc.subject() != null ? doc.subject() : doc.fileName();
                results.add(new SearchResultItem(
                        SearchLayer.NOTICE,
                        titleOf(doc),
                        snippet,
                        null,
                        String.valueOf(doc.id()),
                        "NOTICE",
                        score,
                        null
                ));
            }
        }

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    private double scoreNotice(String query, KnowledgeService.KnowledgeDocumentSummary doc) {
        String title = titleOf(doc).toLowerCase();
        if (title.contains(query)) return 0.85;
        if (doc.subject() != null && doc.subject().toLowerCase().contains(query)) return 0.60;
        if (doc.sender() != null && doc.sender().toLowerCase().contains(query)) return 0.40;
        return 0.0;
    }

    private String titleOf(KnowledgeService.KnowledgeDocumentSummary doc) {
        if (doc.title() != null && !doc.title().isBlank()) return doc.title();
        if (doc.subject() != null && !doc.subject().isBlank()) return doc.subject();
        return doc.fileName() != null ? doc.fileName() : "Notice #" + doc.id();
    }
}
