package ru.andreyz.memoryservice.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchQueryParserTest {

    private final SearchQueryParser parser = new SearchQueryParser();

    @Test
    void parse_removesStopWordsAndBuildsPrefixTsQuery() {
        ParsedSearchQuery parsed = parser.parse("Что зависло по релизу платежей");

        assertThat(parsed.originalQuery()).isEqualTo("Что зависло по релизу платежей");
        assertThat(parsed.normalizedQuery()).isEqualTo("зависло релизу платежей");
        assertThat(parsed.keywords()).containsExactly("зависло", "релизу", "платежей");
        assertThat(parsed.postgresTsQuery()).isEqualTo("зависло:* & релизу:* & платежей:*");
    }

    @Test
    void parse_keepsLowerCasedFallbackWhenOnlyStopWordsRemain() {
        ParsedSearchQuery parsed = parser.parse("И В THE");

        assertThat(parsed.normalizedQuery()).isEqualTo("и в the");
        assertThat(parsed.keywords()).isEmpty();
        assertThat(parsed.postgresTsQuery()).isEmpty();
    }
}
