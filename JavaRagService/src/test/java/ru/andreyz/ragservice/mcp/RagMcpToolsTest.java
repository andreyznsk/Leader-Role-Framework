package ru.andreyz.ragservice.mcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer;
import ru.andreyz.ragservice.indexer.FileIndexer.IndexResult;
import ru.andreyz.ragservice.mcp.RagMcpTools.DirectoryIndexResult;
import ru.andreyz.ragservice.search.RagSearchService;
import ru.andreyz.ragservice.search.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagMcpToolsTest {

    @TempDir
    Path tempDir;

    @Mock
    FileIndexer fileIndexer;
    @Mock
    RagSearchService searchService;
    @Mock
    IndexedDocumentRepository repository;

    RagMcpTools tools;

    @BeforeEach
    void setUp() {
        tools = new RagMcpTools(fileIndexer, searchService, repository);
    }

    // --- ragIndex ---

    @Test
    void ragIndex_delegatesToFileIndexer() throws IOException {
        String path = tempDir.resolve("adr.md").toString();
        IndexResult expected = new IndexResult(3, "indexed", path);
        when(fileIndexer.indexFile(path)).thenReturn(expected);

        IndexResult result = tools.ragIndex(path);

        assertThat(result).isEqualTo(expected);
        verify(fileIndexer).indexFile(path);
    }

    @Test
    void ragIndex_fileNotFound_returnsErrorStatus() throws IOException {
        String path = "/nonexistent/missing.md";
        when(fileIndexer.indexFile(path)).thenThrow(new IOException("File not found: " + path));

        IndexResult result = tools.ragIndex(path);

        assertThat(result.status()).startsWith("error:");
        assertThat(result.chunksAdded()).isEqualTo(0);
        assertThat(result.filePath()).isEqualTo(path);
    }

    @Test
    void ragIndex_skippedFile_returnsSkippedStatus() throws IOException {
        String path = tempDir.resolve("unchanged.md").toString();
        IndexResult skipped = new IndexResult(2, "skipped", path);
        when(fileIndexer.indexFile(path)).thenReturn(skipped);

        IndexResult result = tools.ragIndex(path);

        assertThat(result.status()).isEqualTo("skipped");
    }

    // --- ragSearch ---

    @Test
    void ragSearch_nullTopK_passesNullToService() {
        when(searchService.search("архитектура", null)).thenReturn(List.of());

        tools.ragSearch("архитектура", null);

        verify(searchService).search("архитектура", null);
    }

    @Test
    void ragSearch_explicitTopK_passesThrough() {
        when(searchService.search(anyString(), eq(10))).thenReturn(List.of());

        tools.ragSearch("релиз", 10);

        verify(searchService).search("релиз", 10);
    }

    @Test
    void ragSearch_returnsResultsFromService() {
        SearchResult r = new SearchResult("ADR text.", "adr.md", 0.9, 0);
        when(searchService.search(anyString(), anyInt())).thenReturn(List.of(r));

        List<SearchResult> results = tools.ragSearch("query", 5);

        assertThat(results).containsExactly(r);
    }

    // --- ragStatus ---

    @Test
    void ragStatus_returnsAllDocuments() {
        IndexedDocument d1 = new IndexedDocument(1L, "file1.md", "ADR", "hash1", LocalDateTime.now(), 3, "indexed", null);
        IndexedDocument d2 = new IndexedDocument(2L, "file2.md", "NOTICE", "hash2", LocalDateTime.now(), 5, "indexed", null);
        when(repository.findAll()).thenReturn(List.of(d1, d2));

        List<IndexedDocument> docs = tools.ragStatus();

        assertThat(docs).containsExactly(d1, d2);
    }

    @Test
    void ragStatus_emptyRepository_returnsEmptyList() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(tools.ragStatus()).isEmpty();
    }

    // --- ragIndexDirectory ---

    @Test
    void ragIndexDirectory_nonExistentDir_returnsError() {
        DirectoryIndexResult result = tools.ragIndexDirectory("/no/such/dir", null);

        assertThat(result.message()).contains("directory not found");
        assertThat(result.indexed()).isEqualTo(0);
    }

    @Test
    void ragIndexDirectory_emptyDir_returnsZeroCounts() {
        DirectoryIndexResult result = tools.ragIndexDirectory(tempDir.toString(), null);

        assertThat(result.indexed()).isEqualTo(0);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
        assertThat(result.message()).isEqualTo("done");
    }

    @Test
    void ragIndexDirectory_newFiles_countedAsIndexed() throws Exception {
        Files.writeString(tempDir.resolve("a.md"), "Content for file a.");
        Files.writeString(tempDir.resolve("b.md"), "Content for file b.");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(fileIndexer.indexFile(anyString()))
                .thenReturn(new IndexResult(2, "indexed", "a.md"))
                .thenReturn(new IndexResult(1, "indexed", "b.md"));

        DirectoryIndexResult result = tools.ragIndexDirectory(tempDir.toString(), null);

        assertThat(result.indexed()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
        assertThat(result.failed()).isEqualTo(0);
    }

    @Test
    void ragIndexDirectory_unchangedFiles_countedAsSkipped() throws Exception {
        String content = "Stable content for the document file here.";
        Path file = tempDir.resolve("stable.md");
        Files.writeString(file, content);
        String hash = sha256(content);
        IndexedDocument existing = new IndexedDocument(1L, file.toString(), "SERVICE_CARD", hash, LocalDateTime.now(), 1, "indexed", null);
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        DirectoryIndexResult result = tools.ragIndexDirectory(tempDir.toString(), null);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.indexed()).isEqualTo(0);
        verifyNoInteractions(fileIndexer);
    }

    @Test
    void ragIndexDirectory_failedFile_countedAsFailed() throws Exception {
        Files.writeString(tempDir.resolve("bad.md"), "Failing content.");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(fileIndexer.indexFile(anyString())).thenThrow(new RuntimeException("embed error"));

        DirectoryIndexResult result = tools.ragIndexDirectory(tempDir.toString(), null);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.indexed()).isEqualTo(0);
    }

    @Test
    void ragIndexDirectory_ignoresNonMdFiles() throws Exception {
        Files.writeString(tempDir.resolve("notes.txt"), "Not a markdown file.");
        Files.writeString(tempDir.resolve("data.json"), "{}");

        DirectoryIndexResult result = tools.ragIndexDirectory(tempDir.toString(), null);

        assertThat(result.indexed() + result.skipped() + result.failed()).isEqualTo(0);
        verifyNoInteractions(fileIndexer, repository);
    }

    @Test
    void ragIndexDirectory_customPattern_usedAsSuffix() throws Exception {
        Files.writeString(tempDir.resolve("spec.adoc"), "Asciidoc content here.");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        when(fileIndexer.indexFile(anyString())).thenReturn(new IndexResult(1, "indexed", "spec.adoc"));

        DirectoryIndexResult result = tools.ragIndexDirectory(tempDir.toString(), "*.adoc");

        assertThat(result.indexed()).isEqualTo(1);
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
