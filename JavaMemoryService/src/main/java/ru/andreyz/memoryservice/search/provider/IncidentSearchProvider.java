package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ru.andreyz.memoryservice.repository.IncidentRepository;
import ru.andreyz.memoryservice.search.PostgresSearchRuntime;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;
import ru.andreyz.memoryservice.search.SearchQueryParser;
import ru.andreyz.memoryservice.search.SearchSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class IncidentSearchProvider implements SearchProvider {

    private static final String INCIDENT_SEARCH_SQL = """
            SELECT id, title, severity, status, description, root_cause, action_items, created_at, resolved_at,
                   ts_rank_cd(search_vector, (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))) AS rank
            FROM incidents
            WHERE search_vector @@ (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))
            ORDER BY rank DESC, created_at DESC
            LIMIT :limit
            """;

    private final IncidentRepository incidentRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresSearchRuntime postgresSearchRuntime;
    private final SearchQueryParser searchQueryParser;

    public IncidentSearchProvider(IncidentRepository incidentRepository,
                                  NamedParameterJdbcTemplate jdbcTemplate,
                                  PostgresSearchRuntime postgresSearchRuntime,
                                  SearchQueryParser searchQueryParser) {
        this.incidentRepository = incidentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresSearchRuntime = postgresSearchRuntime;
        this.searchQueryParser = searchQueryParser;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.INCIDENT;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        var parsed = searchQueryParser.parse(query);
        if (postgresSearchRuntime.isPostgres()) {
            return jdbcTemplate.query(INCIDENT_SEARCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("query", parsed.normalizedQuery().isBlank() ? parsed.originalQuery() : parsed.normalizedQuery())
                            .addValue("limit", limit),
                    (rs, rowNum) -> new SearchResultItem(
                            SearchLayer.INCIDENT,
                            rs.getString("title"),
                            SearchSupport.firstNonBlank(rs.getString("description"), rs.getString("root_cause"), rs.getString("action_items")),
                            "/ui/incidents?edit=" + rs.getLong("id") + "#incident-" + rs.getLong("id"),
                            String.valueOf(rs.getLong("id")),
                            "INCIDENT",
                            SearchSupport.clamp((rs.getDouble("rank") * 0.48) + statusBoost(rs.getString("status")) + severityBoost(rs.getString("severity"))),
                            rs.getTimestamp("created_at").toInstant(),
                            SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                    rs.getString("title"),
                                    rs.getString("description"),
                                    rs.getString("root_cause"),
                                    rs.getString("action_items"),
                                    rs.getString("status"),
                                    rs.getString("severity")
                            ))
                    ));
        }

        var results = new ArrayList<SearchResultItem>();

        incidentRepository.findAll().forEach(incident -> {
            double score = 0.0;
            if (SearchSupport.containsAnyKeyword(incident.title(), parsed.keywords())) score += 0.55;
            if (SearchSupport.containsAnyKeyword(incident.description(), parsed.keywords())) score += 0.16;
            if (SearchSupport.containsAnyKeyword(incident.rootCause(), parsed.keywords())) score += 0.10;
            if (SearchSupport.containsAnyKeyword(incident.actionItems(), parsed.keywords())) score += 0.08;
            score += statusBoost(incident.status()) + severityBoost(incident.severity());
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.INCIDENT,
                        incident.title(),
                        incident.description(),
                        "/ui/incidents?edit=" + incident.id() + "#incident-" + incident.id(),
                        String.valueOf(incident.id()),
                        "INCIDENT",
                        SearchSupport.clamp(score),
                        incident.createdAt(),
                        SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                incident.title(), incident.description(), incident.rootCause(), incident.actionItems(),
                                incident.status(), incident.severity()
                        ))
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    private static double statusBoost(String status) {
        return switch (status == null ? "" : status) {
            case "OPEN", "INVESTIGATING" -> 0.16;
            case "MITIGATING" -> 0.10;
            case "RESOLVED", "CLOSED" -> -0.08;
            default -> 0.0;
        };
    }

    private static double severityBoost(String severity) {
        return switch (severity == null ? "" : severity) {
            case "P1", "CRITICAL" -> 0.18;
            case "P2", "HIGH" -> 0.12;
            case "P3", "MEDIUM" -> 0.06;
            default -> 0.0;
        };
    }

    private static Map<String, String> orderedFields(String title, String description, String rootCause,
                                                     String actionItems, String status, String severity) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("rootCause", rootCause);
        fields.put("actionItems", actionItems);
        fields.put("status", status);
        fields.put("severity", severity);
        return fields;
    }
}
