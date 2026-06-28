package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.repository.RiskRepository;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;

import java.util.ArrayList;
import java.util.List;

@Component
public class RiskSearchProvider implements SearchProvider {

    private final RiskRepository riskRepository;

    public RiskSearchProvider(RiskRepository riskRepository) {
        this.riskRepository = riskRepository;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.RISK;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        String q = query.toLowerCase();
        var results = new ArrayList<SearchResultItem>();

        riskRepository.findAll().forEach(risk -> {
            double score = TaskSearchProvider.scoreText(q, risk.title(), risk.description());
            if (score == 0 && risk.mitigation() != null && risk.mitigation().toLowerCase().contains(q)) {
                score = 0.40;
            }
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.RISK,
                        risk.title(),
                        risk.description(),
                        "/ui/risks",
                        String.valueOf(risk.id()),
                        "RISK",
                        score,
                        risk.updatedAt()
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }
}
