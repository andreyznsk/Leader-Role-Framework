package ru.andreyz.memoryservice.search;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SearchPromptBuilder {

    private static final int MAX_RESULTS_IN_PROMPT = 10;
    private static final int MAX_SNIPPET_LENGTH = 300;

    public String build(String query, List<SearchResultItem> results) {
        var sb = new StringBuilder();
        sb.append("Ты — LeaderOS Knowledge Assistant.\n");
        sb.append("Пользователь ищет: \"").append(query).append("\".\n\n");
        sb.append("Сформируй краткий ответ:\n");
        sb.append("1. Что найдено.\n");
        sb.append("2. Где главный источник.\n");
        sb.append("3. Что открыть первым.\n");
        sb.append("4. Есть ли неопределённость.\n\n");
        sb.append("Используй только результаты ниже. Не выдумывай факты.\n\n");
        sb.append("RESULTS:\n");

        results.stream()
                .limit(MAX_RESULTS_IN_PROMPT)
                .forEach(item -> {
                    sb.append("[").append(item.layer()).append("]\n");
                    sb.append("Title: ").append(item.title()).append("\n");
                    if (item.snippet() != null && !item.snippet().isBlank()) {
                        String snippet = item.snippet().length() > MAX_SNIPPET_LENGTH
                                ? item.snippet().substring(0, MAX_SNIPPET_LENGTH) + "..."
                                : item.snippet();
                        sb.append("Snippet: ").append(snippet).append("\n");
                    }
                    if (item.url() != null && !item.url().isBlank()) {
                        sb.append("URL: ").append(item.url()).append("\n");
                    }
                    sb.append("Score: ").append(String.format("%.2f", item.score())).append("\n\n");
                });

        return sb.toString();
    }
}
