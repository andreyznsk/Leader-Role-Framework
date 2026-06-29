package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ru.andreyz.memoryservice.repository.TaskRepository;
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
public class TaskSearchProvider implements SearchProvider {

    private static final String TASK_SEARCH_SQL = """
            SELECT id, title, description, status, priority, due_date, source, updated_at,
                   ts_rank_cd(search_vector, (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))) AS rank
            FROM tasks
            WHERE status <> 'DELETED'
              AND search_vector @@ (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))
            ORDER BY rank DESC, updated_at DESC
            LIMIT :limit
            """;

    private final TaskRepository taskRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresSearchRuntime postgresSearchRuntime;
    private final SearchQueryParser searchQueryParser;

    public TaskSearchProvider(TaskRepository taskRepository,
                              NamedParameterJdbcTemplate jdbcTemplate,
                              PostgresSearchRuntime postgresSearchRuntime,
                              SearchQueryParser searchQueryParser) {
        this.taskRepository = taskRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresSearchRuntime = postgresSearchRuntime;
        this.searchQueryParser = searchQueryParser;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.TASK;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        var parsed = searchQueryParser.parse(query);
        if (postgresSearchRuntime.isPostgres()) {
            return jdbcTemplate.query(TASK_SEARCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("query", parsed.normalizedQuery().isBlank() ? parsed.originalQuery() : parsed.normalizedQuery())
                            .addValue("limit", limit),
                    (rs, rowNum) -> {
                        List<String> matchedFields = SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                rs.getString("title"),
                                rs.getString("description"),
                                rs.getString("status"),
                                rs.getString("priority"),
                                rs.getString("source")
                        ));
                        double finalScore = SearchSupport.clamp(
                                (rs.getDouble("rank") * 0.45)
                                        + statusBoost(rs.getString("status"))
                                        + priorityBoost(rs.getString("priority"))
                                        + dueDateBoost(rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate())
                        );
                        return new SearchResultItem(
                                SearchLayer.TASK,
                                rs.getString("title"),
                                SearchSupport.firstNonBlank(rs.getString("description"), rs.getString("status")),
                                "/ui/today",
                                String.valueOf(rs.getLong("id")),
                                "TASK",
                                finalScore,
                                rs.getTimestamp("updated_at").toInstant(),
                                matchedFields
                        );
                    });
        }

        var results = new ArrayList<SearchResultItem>();
        taskRepository.findCurrentTasks().forEach(task -> {
            double score = fallbackScore(parsed.keywords(), task.title(), task.description(), task.status(), task.priority());
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.TASK,
                        task.title(),
                        task.description(),
                        "/ui/today",
                        String.valueOf(task.id()),
                        "TASK",
                        score,
                        task.updatedAt(),
                        SearchSupport.matchedFields(parsed.keywords(), orderedFields(task.title(), task.description(), task.status(), task.priority(), task.source()))
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    static double scoreText(String query, String title, String body) {
        if (title != null && title.toLowerCase().contains(query)) return 0.85;
        if (body != null && body.toLowerCase().contains(query)) return 0.50;
        return 0.0;
    }

    private double fallbackScore(List<String> keywords, String title, String body, String status, String priority) {
        if (keywords.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        if (SearchSupport.containsAnyKeyword(title, keywords)) score += 0.55;
        if (SearchSupport.containsAnyKeyword(body, keywords)) score += 0.20;
        score += statusBoost(status);
        score += priorityBoost(priority);
        return SearchSupport.clamp(score);
    }

    private static double statusBoost(String status) {
        return switch (status == null ? "" : status) {
            case "IN_PROGRESS" -> 0.18;
            case "BLOCKED" -> 0.16;
            case "TODO", "OPEN" -> 0.10;
            case "DONE" -> -0.10;
            default -> 0.0;
        };
    }

    private static double priorityBoost(String priority) {
        return switch (priority == null ? "" : priority) {
            case "HIGH", "CRITICAL" -> 0.14;
            case "NORMAL", "MEDIUM" -> 0.06;
            case "LOW" -> 0.0;
            default -> 0.0;
        };
    }

    private static double dueDateBoost(java.time.LocalDate dueDate) {
        if (dueDate == null) {
            return 0.0;
        }
        if (dueDate.isBefore(java.time.LocalDate.now())) {
            return 0.10;
        }
        if (dueDate.equals(java.time.LocalDate.now())) {
            return 0.05;
        }
        return 0.0;
    }

    private static Map<String, String> orderedFields(String title, String description, String status, String priority, String source) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("status", status);
        fields.put("priority", priority);
        fields.put("source", source);
        return fields;
    }
}
