package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.TaskDescription;
import ru.andreyz.memoryservice.repository.TaskDescriptionRepository;
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
                   content_md, task_rank, description_rank
            FROM (
                SELECT t.id, t.title, t.description, t.status, t.priority, t.due_date, t.source, t.updated_at,
                       d.content_md,
                       ts_rank_cd(t.search_vector, (to_tsquery('russian', :tsQuery) || to_tsquery('english', :tsQuery))) AS task_rank,
                       ts_rank_cd(d.search_vector, (to_tsquery('russian', :tsQuery) || to_tsquery('english', :tsQuery))) AS description_rank
                FROM tasks t
                LEFT JOIN task_descriptions d ON d.task_id = t.id
                WHERE t.status NOT IN ('DELETED', 'ARCHIVED')
                  AND (
                        t.search_vector @@ (to_tsquery('russian', :tsQuery) || to_tsquery('english', :tsQuery))
                        OR d.search_vector @@ (to_tsquery('russian', :tsQuery) || to_tsquery('english', :tsQuery))
                  )
            ) sub
            ORDER BY ((COALESCE(task_rank, 0) * 0.50) + (COALESCE(description_rank, 0) * 0.35)) DESC, updated_at DESC
            LIMIT :limit
            """;

    private final TaskRepository taskRepository;
    private final TaskDescriptionRepository taskDescriptionRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresSearchRuntime postgresSearchRuntime;
    private final SearchQueryParser searchQueryParser;

    public TaskSearchProvider(TaskRepository taskRepository,
                              TaskDescriptionRepository taskDescriptionRepository,
                              NamedParameterJdbcTemplate jdbcTemplate,
                              PostgresSearchRuntime postgresSearchRuntime,
                              SearchQueryParser searchQueryParser) {
        this.taskRepository = taskRepository;
        this.taskDescriptionRepository = taskDescriptionRepository;
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
            if (parsed.postgresTsQuery().isBlank()) {
                return List.of();
            }
            return jdbcTemplate.query(TASK_SEARCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("tsQuery", parsed.postgresTsQuery())
                            .addValue("limit", limit),
                    (rs, rowNum) -> {
                        String contentMd = rs.getString("content_md");
                        List<String> matchedFields = SearchSupport.matchedFields(parsed.keywords(), orderedFields(
                                rs.getString("title"),
                                rs.getString("description"),
                                contentMd,
                                rs.getString("status"),
                                rs.getString("priority"),
                                rs.getString("source")
                        ));
                        double finalScore = SearchSupport.clamp(
                                (rs.getDouble("task_rank") * 0.45)
                                        + (rs.getDouble("description_rank") * 0.30)
                                        + statusBoost(rs.getString("status"))
                                        + priorityBoost(rs.getString("priority"))
                                        + dueDateBoost(rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate())
                        );
                        return new SearchResultItem(
                                SearchLayer.TASK,
                                rs.getString("title"),
                                buildSnippet(parsed.keywords(), contentMd, rs.getString("description"), rs.getString("status")),
                                "/ui/tasks/" + rs.getLong("id") + "/edit",
                                String.valueOf(rs.getLong("id")),
                                "TASK",
                                finalScore,
                                rs.getTimestamp("updated_at").toInstant(),
                                matchedFields
                        );
                    });
        }

        var results = new ArrayList<SearchResultItem>();
        var tasks = taskRepository.findCurrentTasks();
        Map<Long, TaskDescription> descriptionsByTaskId = taskDescriptionRepository.findByTaskIdIn(
                tasks.stream().map(task -> task.id()).toList()
        ).stream().collect(java.util.stream.Collectors.toMap(TaskDescription::taskId, java.util.function.Function.identity()));
        tasks.forEach(task -> {
            String contentMd = descriptionsByTaskId.containsKey(task.id())
                    ? descriptionsByTaskId.get(task.id()).contentMd()
                    : null;
            double score = fallbackScore(parsed.keywords(), task.title(), task.description(), contentMd, task.status(), task.priority());
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.TASK,
                        task.title(),
                        buildSnippet(parsed.keywords(), contentMd, task.description(), task.status()),
                        "/ui/tasks/" + task.id() + "/edit",
                        String.valueOf(task.id()),
                        "TASK",
                        score,
                        task.updatedAt(),
                        SearchSupport.matchedFields(parsed.keywords(), orderedFields(task.title(), task.description(), contentMd, task.status(), task.priority(), task.source()))
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

    private double fallbackScore(List<String> keywords, String title, String body, String contentMd, String status, String priority) {
        if (keywords.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        if (SearchSupport.containsAnyKeyword(title, keywords)) score += 0.55;
        if (SearchSupport.containsAnyKeyword(body, keywords)) score += 0.20;
        if (SearchSupport.containsAnyKeyword(contentMd, keywords)) score += 0.30;
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

    private static Map<String, String> orderedFields(String title, String description, String contentMd, String status, String priority, String source) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("title", title);
        fields.put("description", description);
        fields.put("contentMd", contentMd);
        fields.put("status", status);
        fields.put("priority", priority);
        fields.put("source", source);
        return fields;
    }

    private static String buildSnippet(List<String> keywords, String contentMd, String description, String status) {
        String preferred = snippetFromContent(keywords, SearchSupport.firstNonBlank(contentMd, description));
        return SearchSupport.firstNonBlank(preferred, description, status);
    }

    private static String snippetFromContent(List<String> keywords, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        String normalized = compact.toLowerCase();
        for (String keyword : keywords) {
            int idx = normalized.indexOf(keyword.toLowerCase());
            if (idx >= 0) {
                int start = Math.max(0, idx - 48);
                int end = Math.min(compact.length(), idx + keyword.length() + 96);
                return compact.substring(start, end);
            }
        }
        return compact.length() <= 160 ? compact : compact.substring(0, 157) + "...";
    }
}
