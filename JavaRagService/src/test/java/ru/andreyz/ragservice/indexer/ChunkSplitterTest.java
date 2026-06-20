package ru.andreyz.ragservice.indexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkSplitterTest {

    @Test
    void splitsLongChunkUsingConfiguredMaxSize() {
        RagChunkProperties properties = new RagChunkProperties();
        properties.setMinSize(10);
        properties.setMaxSize(512);
        ChunkSplitter splitter = new ChunkSplitter(properties);

        String content = ("A".repeat(300) + ". " + "B".repeat(300) + ". " + "C".repeat(300) + ".").trim();

        List<String> chunks = splitter.split(content);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).length()).isLessThanOrEqualTo(512);
    }
}
