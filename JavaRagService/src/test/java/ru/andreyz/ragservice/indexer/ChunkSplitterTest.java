package ru.andreyz.ragservice.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkSplitterTest {

    private ChunkSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new ChunkSplitter();
    }

    @Test
    void split_emptyContent_returnsEmptyList() {
        List<String> chunks = splitter.split("");
        assertThat(chunks).isEmpty();
    }

    @Test
    void split_singleShortParagraph_returnsSingleChunk() {
        String content = "Short text.";
        List<String> chunks = splitter.split(content);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("Short text.");
    }

    @Test
    void split_twoParagraphsBothLong_returnsTwo() {
        String p1 = "A".repeat(50) + ". " + "B".repeat(50) + ".";
        String p2 = "C".repeat(50) + ". " + "D".repeat(50) + ".";
        String content = p1 + "\n\n" + p2;

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains(p1);
        assertThat(chunks.get(1)).contains(p2);
    }

    @Test
    void split_shortFirstParagraph_mergedWithSecond() {
        // First paragraph is < 100 chars, should be merged
        String short1 = "Too short.";
        String long2 = "C".repeat(50) + ". " + "D".repeat(50) + ".";
        String content = short1 + "\n\n" + long2;

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains(short1);
        assertThat(chunks.get(0)).contains(long2);
    }

    @Test
    void split_multipleShortParagraphs_mergedUntilMinSize() {
        String short1 = "First short.";
        String short2 = "Second short.";
        String long3 = "L".repeat(50) + ". " + "M".repeat(50) + ".";
        String content = short1 + "\n\n" + short2 + "\n\n" + long3;

        List<String> chunks = splitter.split(content);

        // All three get merged since first two are short
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains(short1).contains(short2).contains(long3);
    }

    @Test
    void split_veryLongParagraph_splitAtSentenceBoundary() {
        // Build a paragraph > 1000 chars with clear sentence boundaries
        String sentence = "This is a complete sentence with enough words. ";
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 1200) sb.append(sentence);
        String content = sb.toString();

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(1000 + 200); // +200 for overlap
        }
    }

    @Test
    void split_multipleChunks_secondChunkHasOverlap() {
        // p1: 80 filler chars + ". " + unique last sentence
        // extractLastSentence(p1) returns the text after the second-to-last '.', which is the unique last sentence
        String p1 = "A".repeat(80) + ". Last sentence of p1.";   // 104 chars ≥ 100
        String p2 = "B".repeat(80) + ". Content of p2 paragraph."; // 107 chars ≥ 100
        String content = p1 + "\n\n" + p2;

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSize(2);
        // Second chunk should include overlap (last sentence of p1)
        assertThat(chunks.get(1)).contains("Last sentence of p1");
        // And also contain p2's own content
        assertThat(chunks.get(1)).contains("Content of p2 paragraph");
    }

    @Test
    void split_threeChunks_eachHasOverlapFromPrevious() {
        // Each paragraph: 80 A/B/C chars + ". " + unique last sentence
        String p1 = "A".repeat(80) + ". Last sentence of p1.";    // 104 chars
        String p2 = "B".repeat(80) + ". Last sentence of p2.";    // 104 chars
        String p3 = "C".repeat(80) + ". Content of p3 paragraph."; // 107 chars
        String content = p1 + "\n\n" + p2 + "\n\n" + p3;

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSize(3);
        // chunk[0] is pure p1 — no B or C content
        assertThat(chunks.get(0)).doesNotContain("B").doesNotContain("Last sentence of p2");
        // chunk[1] has overlap (last sentence of p1) + p2 content
        assertThat(chunks.get(1)).contains("Last sentence of p1");
        assertThat(chunks.get(1)).contains("Last sentence of p2");
        // chunk[2] has overlap (last sentence of chunk[1]) + p3 content
        // chunk[1] ends with "Last sentence of p2." → that's what propagates
        assertThat(chunks.get(2)).contains("Last sentence of p2");
        assertThat(chunks.get(2)).contains("Content of p3 paragraph");
    }

    @Test
    void split_singleChunk_noOverlapAdded() {
        String content = "Only one paragraph. It has enough characters. " + "X".repeat(60) + ".";

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSize(1);
        // No duplicate content from overlap
        assertThat(chunks.get(0)).isEqualTo(content.trim());
    }

    @Test
    void split_multipleNewlines_treatedAsOneSeparator() {
        String p1 = "P".repeat(50) + ". " + "Q".repeat(50) + ".";
        String p2 = "R".repeat(50) + ". " + "S".repeat(50) + ".";
        String content = p1 + "\n\n\n\n" + p2;

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSize(2);
    }

    @Test
    void split_chunkTextContainsOriginalWords() {
        String content = "PostgreSQL is used for storage. It provides ACID guarantees.\n\n" +
                "Flyway manages schema migrations. All services use it. " + "Z".repeat(50) + ".";

        List<String> chunks = splitter.split(content);

        String allText = String.join(" ", chunks);
        assertThat(allText).contains("PostgreSQL");
        assertThat(allText).contains("Flyway");
        assertThat(allText).contains("ACID");
    }
}
