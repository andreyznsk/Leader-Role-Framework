package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import ru.andreyz.memoryservice.domain.Person;
import ru.andreyz.memoryservice.repository.PersonRepository;
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
public class PeopleSearchProvider implements SearchProvider {

    private static final String PEOPLE_SEARCH_SQL = """
            SELECT id, full_name, login, email, phone, domain, current_task, notes, created_at, updated_at,
                   ts_rank_cd(search_vector, (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))) AS rank
            FROM people
            WHERE search_vector @@ (websearch_to_tsquery('russian', :query) || websearch_to_tsquery('english', :query))
            ORDER BY rank DESC, updated_at DESC
            LIMIT :limit
            """;

    private final PersonRepository personRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final PostgresSearchRuntime postgresSearchRuntime;
    private final SearchQueryParser searchQueryParser;

    public PeopleSearchProvider(PersonRepository personRepository,
                                NamedParameterJdbcTemplate jdbcTemplate,
                                PostgresSearchRuntime postgresSearchRuntime,
                                SearchQueryParser searchQueryParser) {
        this.personRepository = personRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.postgresSearchRuntime = postgresSearchRuntime;
        this.searchQueryParser = searchQueryParser;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.PEOPLE;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        var parsed = searchQueryParser.parse(query);
        if (postgresSearchRuntime.isPostgres()) {
            return jdbcTemplate.query(PEOPLE_SEARCH_SQL,
                    new MapSqlParameterSource()
                            .addValue("query", parsed.normalizedQuery().isBlank() ? parsed.originalQuery() : parsed.normalizedQuery())
                            .addValue("limit", limit),
                    (rs, rowNum) -> {
                        Person person = new Person(
                                rs.getLong("id"),
                                rs.getString("full_name"),
                                rs.getString("login"),
                                rs.getString("email"),
                                rs.getString("phone"),
                                rs.getString("domain"),
                                rs.getString("current_task"),
                                null,
                                null,
                                null,
                                rs.getString("notes"),
                                rs.getTimestamp("created_at").toInstant(),
                                rs.getTimestamp("updated_at").toInstant()
                        );
                        return new SearchResultItem(
                                SearchLayer.PEOPLE,
                                person.fullName(),
                                buildSnippet(person),
                                "/ui/people",
                                String.valueOf(person.id()),
                                "PERSON",
                                SearchSupport.clamp((rs.getDouble("rank") * 0.50) + nameBoost(person, parsed.keywords())),
                                person.updatedAt(),
                                SearchSupport.matchedFields(parsed.keywords(), orderedFields(person))
                        );
                    });
        }

        var results = new ArrayList<SearchResultItem>();
        personRepository.findAll().forEach(person -> {
            double score = scorePerson(parsed.keywords(), person);
            if (score > 0) {
                String snippet = buildSnippet(person);
                results.add(new SearchResultItem(
                        SearchLayer.PEOPLE,
                        person.fullName(),
                        snippet,
                        "/ui/people",
                        String.valueOf(person.id()),
                        "PERSON",
                        score,
                        person.updatedAt(),
                        SearchSupport.matchedFields(parsed.keywords(), orderedFields(person))
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    private double scorePerson(List<String> keywords, Person person) {
        if (keywords.isEmpty()) {
            return 0.0;
        }
        double score = nameBoost(person, keywords);
        if (SearchSupport.containsAnyKeyword(person.domain(), keywords)) score += 0.18;
        if (SearchSupport.containsAnyKeyword(person.currentTask(), keywords)) score += 0.12;
        if (SearchSupport.containsAnyKeyword(person.notes(), keywords)) score += 0.10;
        return SearchSupport.clamp(score);
    }

    private String buildSnippet(Person person) {
        var parts = new ArrayList<String>();
        if (person.domain() != null) parts.add(person.domain());
        if (person.currentTask() != null) parts.add(person.currentTask());
        if (person.notes() != null) parts.add(person.notes());
        return String.join(" · ", parts);
    }

    private static double nameBoost(Person person, List<String> keywords) {
        if (SearchSupport.containsAnyKeyword(person.fullName(), keywords)) {
            return 0.42;
        }
        if (SearchSupport.containsAnyKeyword(person.login(), keywords) || SearchSupport.containsAnyKeyword(person.email(), keywords)) {
            return 0.24;
        }
        return 0.0;
    }

    private static Map<String, String> orderedFields(Person person) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("fullName", person.fullName());
        fields.put("login", person.login());
        fields.put("email", person.email());
        fields.put("domain", person.domain());
        fields.put("currentTask", person.currentTask());
        fields.put("notes", person.notes());
        return fields;
    }
}
