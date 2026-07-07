package ru.andreyz.memoryservice.search.provider;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.repository.NoteRepository;
import ru.andreyz.memoryservice.search.PostgresSearchRuntime;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchQueryParser;
import ru.andreyz.memoryservice.search.SearchResultItem;
import ru.andreyz.memoryservice.search.SearchSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class NoteSearchProvider implements SearchProvider {

    private static final String NOTE_SEARCH_SQL = """
            SELECT id, title, text, tags, source, created_at,
                   ts_rank_cd(search_vector, (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))) AS rank
            FROM notes
            WHERE search_vector @@ (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))
            ORDER BY rank DESC, created_at DESC
            LIMIT :limit
            """;

    private final NoteRepository noteRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresSearchRuntime postgresSearchRuntime;
    private final SearchQueryParser searchQueryParser;

    public NoteSearchProvider(NoteRepository noteRepository,
                              NamedParameterJdbcTemplate jdbcTemplate,
                              PostgresSearchRuntime postgresSearchRuntime,
                              SearchQueryParser searchQueryParser) {
        this.noteRepository = noteRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresSearchRuntime = postgresSearchRuntime;
        this.searchQueryParser = searchQueryParser;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.NOTE;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        var parsed = searchQueryParser.parse(query);
        if (postgresSearchRuntime.isPostgres()) {
            return jdbcTemplate.query(NOTE_SEARCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("query", parsed.normalizedQuery().isBlank() ? parsed.originalQuery() : parsed.normalizedQuery())
                            .addValue("limit", limit),
                    (rs, rowNum) -> new SearchResultItem(
                            SearchLayer.NOTE,
                            rs.getString("title"),
                            SearchSupport.firstNonBlank(rs.getString("text"), rs.getString("tags"), rs.getString("source")),
                            "/ui/notes?edit=" + rs.getLong("id") + "#note-" + rs.getLong("id"),
                            String.valueOf(rs.getLong("id")),
                            "NOTE",
                            SearchSupport.clamp((rs.getDouble("rank") * 0.55) + sourceBoost(rs.getString("source"))),
                            rs.getTimestamp("created_at").toInstant(),
                            SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                    rs.getString("title"),
                                    rs.getString("text"),
                                    rs.getString("tags"),
                                    rs.getString("source")
                            ))
                    ));
        }

        var results = new ArrayList<SearchResultItem>();
        noteRepository.findTop200ByOrderByCreatedAtDesc().forEach(note -> {
            double score = 0.0;
            if (SearchSupport.containsAnyKeyword(note.title(), parsed.keywords())) score += 0.58;
            if (SearchSupport.containsAnyKeyword(note.text(), parsed.keywords())) score += 0.18;
            if (SearchSupport.containsAnyKeyword(note.tags(), parsed.keywords())) score += 0.12;
            score += sourceBoost(note.source());
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.NOTE,
                        note.title(),
                        SearchSupport.firstNonBlank(note.text(), note.tags(), note.source()),
                        "/ui/notes?edit=" + note.id() + "#note-" + note.id(),
                        String.valueOf(note.id()),
                        "NOTE",
                        SearchSupport.clamp(score),
                        note.createdAt(),
                        SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                note.title(), note.text(), note.tags(), note.source()
                        ))
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    private static double sourceBoost(String source) {
        return switch (source == null ? "" : source) {
            case "email", "capture" -> 0.10;
            default -> 0.0;
        };
    }

    private static Map<String, String> orderedFields(String title, String text, String tags, String source) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("text", text);
        fields.put("tags", tags);
        fields.put("source", source);
        return fields;
    }
}
