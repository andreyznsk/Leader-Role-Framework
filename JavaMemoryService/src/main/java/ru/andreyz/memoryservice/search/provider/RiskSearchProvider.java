package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ru.andreyz.memoryservice.repository.RiskRepository;
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
public class RiskSearchProvider implements SearchProvider {

    private static final String RISK_SEARCH_SQL = """
            SELECT id, title, description, impact, status, mitigation, updated_at,
                   ts_rank_cd(search_vector, (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))) AS rank
            FROM risks
            WHERE search_vector @@ (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))
            ORDER BY rank DESC, updated_at DESC
            LIMIT :limit
            """;

    private final RiskRepository riskRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresSearchRuntime postgresSearchRuntime;
    private final SearchQueryParser searchQueryParser;

    public RiskSearchProvider(RiskRepository riskRepository,
                              NamedParameterJdbcTemplate jdbcTemplate,
                              PostgresSearchRuntime postgresSearchRuntime,
                              SearchQueryParser searchQueryParser) {
        this.riskRepository = riskRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresSearchRuntime = postgresSearchRuntime;
        this.searchQueryParser = searchQueryParser;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.RISK;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        var parsed = searchQueryParser.parse(query);
        if (postgresSearchRuntime.isPostgres()) {
            return jdbcTemplate.query(RISK_SEARCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("query", parsed.normalizedQuery().isBlank() ? parsed.originalQuery() : parsed.normalizedQuery())
                            .addValue("limit", limit),
                    (rs, rowNum) -> new SearchResultItem(
                            SearchLayer.RISK,
                            rs.getString("title"),
                            SearchSupport.firstNonBlank(rs.getString("description"), rs.getString("mitigation"), rs.getString("status")),
                            "/ui/risks?edit=" + rs.getLong("id") + "#risk-" + rs.getLong("id"),
                            String.valueOf(rs.getLong("id")),
                            "RISK",
                            SearchSupport.clamp((rs.getDouble("rank") * 0.50) + statusBoost(rs.getString("status")) + impactBoost(rs.getString("impact"))),
                            rs.getTimestamp("updated_at").toInstant(),
                            SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                    rs.getString("title"),
                                    rs.getString("description"),
                                    rs.getString("impact"),
                                    rs.getString("mitigation"),
                                    rs.getString("status")
                            ))
                    ));
        }

        var results = new ArrayList<SearchResultItem>();

        riskRepository.findAll().forEach(risk -> {
            double score = 0.0;
            if (SearchSupport.containsAnyKeyword(risk.title(), parsed.keywords())) score += 0.55;
            if (SearchSupport.containsAnyKeyword(risk.description(), parsed.keywords())) score += 0.18;
            if (SearchSupport.containsAnyKeyword(risk.mitigation(), parsed.keywords())) score += 0.12;
            score += statusBoost(risk.status()) + impactBoost(risk.impact());
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.RISK,
                        risk.title(),
                        risk.description(),
                        "/ui/risks?edit=" + risk.id() + "#risk-" + risk.id(),
                        String.valueOf(risk.id()),
                        "RISK",
                        SearchSupport.clamp(score),
                        risk.updatedAt(),
                        SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                risk.title(), risk.description(), risk.impact(), risk.mitigation(), risk.status()
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
            case "OPEN" -> 0.16;
            case "WATCH", "MITIGATING" -> 0.10;
            case "MITIGATED", "CLOSED" -> -0.08;
            default -> 0.0;
        };
    }

    private static double impactBoost(String impact) {
        return switch (impact == null ? "" : impact) {
            case "HIGH", "CRITICAL" -> 0.16;
            case "MEDIUM" -> 0.08;
            default -> 0.0;
        };
    }

    private static Map<String, String> orderedFields(String title, String description, String impact, String mitigation, String status) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("impact", impact);
        fields.put("mitigation", mitigation);
        fields.put("status", status);
        return fields;
    }
}
