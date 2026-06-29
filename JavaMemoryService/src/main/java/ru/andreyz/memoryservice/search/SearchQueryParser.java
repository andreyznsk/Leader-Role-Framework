package ru.andreyz.memoryservice.search;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class SearchQueryParser {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "in", "is", "it", "of", "on", "or",
            "that", "the", "this", "to", "with",
            "без", "был", "была", "были", "в", "во", "где", "для", "до", "и", "из", "или", "к", "как", "кто",
            "ли", "на", "над", "не", "но", "о", "об", "от", "по", "под", "при", "про", "с", "со", "у", "что",
            "это", "эти", "этот", "я"
    );

    public ParsedSearchQuery parse(String rawQuery) {
        String original = rawQuery == null ? "" : rawQuery.trim();
        String lowered = original.toLowerCase(Locale.ROOT);
        List<String> keywords = NON_WORD.matcher(lowered).replaceAll(" ").trim().lines()
                .flatMap(line -> java.util.Arrays.stream(line.split("\\s+")))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> !STOP_WORDS.contains(token))
                .toList();

        String normalized = keywords.isEmpty() ? lowered : String.join(" ", keywords);
        String postgresTsQuery = keywords.isEmpty()
                ? ""
                : keywords.stream()
                .map(keyword -> keyword + ":*")
                .reduce((left, right) -> left + " & " + right)
                .orElse("");

        return new ParsedSearchQuery(original, normalized.trim(), keywords, postgresTsQuery);
    }
}
