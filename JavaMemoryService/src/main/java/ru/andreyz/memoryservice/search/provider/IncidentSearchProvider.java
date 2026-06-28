package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.repository.IncidentRepository;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;

import java.util.ArrayList;
import java.util.List;

@Component
public class IncidentSearchProvider implements SearchProvider {

    private final IncidentRepository incidentRepository;

    public IncidentSearchProvider(IncidentRepository incidentRepository) {
        this.incidentRepository = incidentRepository;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.INCIDENT;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        String q = query.toLowerCase();
        var results = new ArrayList<SearchResultItem>();

        incidentRepository.findAll().forEach(incident -> {
            double score = TaskSearchProvider.scoreText(q, incident.title(), incident.description());
            if (score == 0 && incident.rootCause() != null && incident.rootCause().toLowerCase().contains(q)) {
                score = 0.40;
            }
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.INCIDENT,
                        incident.title(),
                        incident.description(),
                        "/ui/incidents",
                        String.valueOf(incident.id()),
                        "INCIDENT",
                        score,
                        incident.createdAt()
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }
}
