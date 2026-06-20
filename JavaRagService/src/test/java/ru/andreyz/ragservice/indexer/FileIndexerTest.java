package ru.andreyz.ragservice.indexer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer.IndexResult;
import ru.andreyz.ragservice.validation.DocumentValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileIndexerTest {

    @TempDir
    Path tempDir;

    @Mock OllamaClient ollama;
    @Mock OpenSearchClient openSearch;
    @Mock IndexedDocumentRepository repository;

    FileIndexer indexer;

    @BeforeEach
    void setUp() {
        RagChunkProperties chunkProperties = new RagChunkProperties();
        indexer = new FileIndexer(new ChunkSplitter(chunkProperties), ollama, openSearch, repository, new DocumentValidator());
        lenient().when(ollama.embed(anyString())).thenReturn(new float[1024]);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void indexFile_newFile_statusIndexed() throws IOException {
        Path file = writeFile("new.md", validServiceCard("New"));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        assertThat(result.status()).isEqualTo("indexed");
        assertThat(result.filePath()).isEqualTo(file.toString());
        assertThat(result.chunksAdded()).isGreaterThan(0);
    }

    @Test
    void indexFile_newFile_savesDocumentToRepository() throws IOException {
        Path file = writeFile("adr.md", validAdr("Content"));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        indexer.indexFile(file.toString());

        verify(repository).save(argThat(doc ->
                doc.filePath().equals(file.toString()) &&
                "ADR".equals(doc.docType()) &&
                doc.status().equals("indexed") &&
                doc.chunkCount() > 0
        ));
    }

    @Test
    void indexFile_newFile_callsEmbedForEachChunk() throws IOException {
        Path file = writeFile("multi.md", validServiceCard(buildLongContent(3)));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        verify(ollama, times(result.chunksAdded())).embed(anyString());
    }

    @Test
    void indexFile_newFile_indexesDocumentInOpenSearch() throws IOException {
        Path file = writeFile("service.md", validServiceCard("Service card content."));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        verify(openSearch, times(result.chunksAdded()))
                .indexDocument(anyString(), anyString(), any(float[].class),
                        eq(file.toString()), eq("service"), anyInt());
    }

    @Test
    void indexFile_sameHash_skipped() throws IOException {
        String content = validServiceCard("Unchanged.");
        Path file = writeFile("unchanged.md", content);
        String hash = sha256(content);
        IndexedDocument existing = new IndexedDocument(1L, file.toString(), "SERVICE_CARD", hash, null, 2, "indexed", null);
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        IndexResult result = indexer.indexFile(file.toString());

        assertThat(result.status()).isEqualTo("skipped");
        assertThat(result.chunksAdded()).isEqualTo(2);
        verifyNoInteractions(ollama, openSearch);
        verify(repository, never()).save(any());
    }

    @Test
    void indexFile_changedHash_deletesOldChunksThenReindexes() throws IOException {
        String newContent = validServiceCard("Updated content.");
        Path file = writeFile("changed.md", newContent);
        IndexedDocument existing = new IndexedDocument(1L, file.toString(), "SERVICE_CARD", "old-hash-value", null, 3, "indexed", null);
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        indexer.indexFile(file.toString());

        verify(openSearch).deleteBySource(file.toString());
        verify(ollama, atLeastOnce()).embed(anyString());
    }

    @Test
    void indexFile_changedHash_updatesExistingRecord() throws IOException {
        String newContent = validServiceCard("Updated ADR.");
        Path file = writeFile("updated.md", newContent);
        IndexedDocument existing = new IndexedDocument(42L, file.toString(), "SERVICE_CARD", "stale-hash", null, 1, "indexed", null);
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        indexer.indexFile(file.toString());

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
        Path file = writeFile("glossary.md", validGlossary("Glossary content."));
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        indexer.indexFile(file.toString());

        verify(openSearch, atLeastOnce()).indexDocument(
                argThat(id -> id.startsWith("glossary_")),
                anyString(), any(float[].class), anyString(), eq("glossary"), anyInt()
        );
    }

    @Test
    void indexFile_invalidDocument_savesInvalidStatusAndSkipsIndexing() throws IOException {
        Path file = writeFile("bad.md", "# No frontmatter here\nSome content.");
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.empty());

        IndexResult result = indexer.indexFile(file.toString());

        assertThat(result.status()).isEqualTo("invalid");
        assertThat(result.chunksAdded()).isEqualTo(0);
        verifyNoInteractions(ollama, openSearch);
        verify(repository).save(argThat(doc ->
                "invalid".equals(doc.status()) &&
                doc.errorMessage() != null && !doc.errorMessage().isBlank()
        ));
    }

    // --- helpers ---

    private Path writeFile(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private String validServiceCard(String extra) {
        return """
                ---
                type: service-card
                service: TST
                updated: 2026-06-11
                review_by: 2026-09-11
                ---
                # TestService (TST)
                ## Назначение
                %s
                ## Стек
                - Java 21
                ## Интеграции
                - none
                ## Деплой
                - Kubernetes
                """.formatted(extra);
    }

    private String validAdr(String extra) {
        return """
                ---
                type: adr
                updated: 2026-06-11
                ---
                # ADR title
                ## Статус
                Accepted
                ## Контекст
                %s
                ## Решение
                Some decision.
                ## Последствия
                Some consequences.
                """.formatted(extra);
    }

    private String validGlossary(String extra) {
        return """
                ---
                type: glossary
                updated: 2026-06-11
                ---
                # Глоссарий
                %s
                """.formatted(extra);
    }

    private String buildLongContent(int paragraphs) {
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
