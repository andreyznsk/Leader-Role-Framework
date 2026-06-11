package ru.andreyz.ragservice.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer.IndexResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
class FileIndexerTest {

    @TempDir
    Path tempDir;

    @Mock
    OllamaClient ollama;
    @Mock
    OpenSearchClient openSearch;
    @Mock
    IndexedDocumentRepository repository;

    FileIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new FileIndexer(new ChunkSplitter(), ollama, openSearch, repository);
        // lenient: not every test exercises both stubs (e.g. skipped/not-found tests)
        lenient().when(ollama.embed(anyString())).thenReturn(new float[1024]);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void indexFile_newFile_statusIndexed() throws IOException {
        Path file = writeFile("new.md", longContent("New file content."));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        assertThat(result.status()).isEqualTo("indexed");
        assertThat(result.filePath()).isEqualTo(file.toString());
        assertThat(result.chunksAdded()).isGreaterThan(0);
    }

    @Test
    void indexFile_newFile_savesDocumentToRepository() throws IOException {
        Path file = writeFile("adr.md", longContent("ADR content."));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        indexer.indexFile(file.toString());

        verify(repository).save(argThat(doc ->
                doc.filePath().equals(file.toString()) &&
                doc.status().equals("indexed") &&
                doc.chunkCount() > 0
        ));
    }

    @Test
    void indexFile_newFile_callsEmbedForEachChunk() throws IOException {
        // Content that splits into multiple chunks
        String content = buildMultiChunkContent(3);
        Path file = writeFile("multi.md", content);
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        verify(ollama, times(result.chunksAdded())).embed(anyString());
    }

    @Test
    void indexFile_newFile_indexesDocumentInOpenSearch() throws IOException {
        Path file = writeFile("service.md", longContent("Service card content."));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        verify(openSearch, times(result.chunksAdded()))
                .indexDocument(anyString(), anyString(), any(float[].class),
                        eq(file.toString()), eq("service"), anyInt());
    }

    @Test
    void indexFile_sameHash_skipped() throws IOException {
        String content = longContent("Unchanged content.");
        Path file = writeFile("unchanged.md", content);
        String hash = sha256(content);
        IndexedDocument existing = new IndexedDocument(1L, file.toString(), hash, null, 2, "indexed");
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        IndexResult result = indexer.indexFile(file.toString());

        assertThat(result.status()).isEqualTo("skipped");
        assertThat(result.chunksAdded()).isEqualTo(2);
        verifyNoInteractions(ollama, openSearch);
        verify(repository, never()).save(any());
    }

    @Test
    void indexFile_changedHash_deletesOldChunksThenReindexes() throws IOException {
        String newContent = longContent("Updated content for the file.");
        Path file = writeFile("changed.md", newContent);
        IndexedDocument existing = new IndexedDocument(1L, file.toString(), "old-hash-value", null, 3, "indexed");
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        indexer.indexFile(file.toString());

        verify(openSearch).deleteBySource(file.toString());
        verify(ollama, atLeastOnce()).embed(anyString());
    }

    @Test
    void indexFile_changedHash_updatesExistingRecord() throws IOException {
        String newContent = longContent("Updated content for the ADR.");
        Path file = writeFile("updated.md", newContent);
        IndexedDocument existing = new IndexedDocument(42L, file.toString(), "stale-hash", null, 1, "indexed");
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        indexer.indexFile(file.toString());

        // Must save a document with the same id (update, not insert)
        verify(repository).save(argThat(doc -> doc.id() != null && doc.id().equals(42L)));
    }

    @Test
    void indexFile_fileNotFound_throwsIOException() {
        String nonExistent = tempDir.resolve("missing.md").toString();

        assertThatThrownBy(() -> indexer.indexFile(nonExistent))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("File not found");
    }

    @Test
    void indexFile_chunkIdContainsFilenameAndIndex() throws IOException {
        Path file = writeFile("glossary.md", longContent("Glossary content here."));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        indexer.indexFile(file.toString());

        verify(openSearch, atLeastOnce()).indexDocument(
                argThat(id -> id.startsWith("glossary_")),
                anyString(), any(float[].class), anyString(), eq("glossary"), anyInt()
        );
    }

    // --- helpers ---

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private String longContent(String seed) {
        return seed + " " + "X".repeat(150) + ". More details here. " + "Y".repeat(150) + ".";
    }

    private String buildMultiChunkContent(int paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs; i++) {
            if (i > 0) sb.append("\n\n");
            sb.append("Paragraph ").append(i).append(". ")
              .append("Content ".repeat(20)).append("end of paragraph ").append(i).append(".");
        }
        return sb.toString();
    }

    private String sha256(String content) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(content.getBytes());
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
