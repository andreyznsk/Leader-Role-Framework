package ru.andreyz.ragservice.indexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexSchedulerTest {

    @TempDir
    Path inbox;

    @Mock
    FileIndexer fileIndexer;
    @Mock
    IndexedDocumentRepository repository;

    @Test
    void scanAndIndex_nonExistentInbox_doesNothing() throws IOException {
        IndexScheduler scheduler = new IndexScheduler("/no/such/directory", fileIndexer, repository);

        scheduler.scanAndIndex();

        verifyNoInteractions(fileIndexer, repository);
    }

    @Test
    void scanAndIndex_emptyInbox_doesNothing() {
        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);

        scheduler.scanAndIndex();

        verifyNoInteractions(fileIndexer, repository);
    }

    @Test
    void scanAndIndex_newFile_callsFileIndexer() throws Exception {
        Files.writeString(inbox.resolve("doc.md"), "New document content here for indexing purposes.");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());

        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);
        scheduler.scanAndIndex();

        verify(fileIndexer).indexFile(anyString());
    }

    @Test
    void scanAndIndex_unchangedFile_skipsIndexer() throws Exception {
        String content = "Stable document content that has not changed at all.";
        Path file = inbox.resolve("stable.md");
        Files.writeString(file, content);
        String hash = sha256(content);
        IndexedDocument existing = new IndexedDocument(1L, file.toString(), hash, null, 1, "indexed");
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(existing));

        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);
        scheduler.scanAndIndex();

        verify(fileIndexer, never()).indexFile(anyString());
    }

    @Test
    void scanAndIndex_changedFile_callsFileIndexer() throws Exception {
        String content = "Updated document content that changed since last index.";
        Path file = inbox.resolve("changed.md");
        Files.writeString(file, content);
        IndexedDocument stale = new IndexedDocument(1L, file.toString(), "old-hash", null, 1, "indexed");
        when(repository.findByFilePath(file.toString())).thenReturn(Optional.of(stale));

        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);
        scheduler.scanAndIndex();

        verify(fileIndexer).indexFile(file.toString());
    }

    @Test
    void scanAndIndex_nonMdFile_ignored() throws Exception {
        Files.writeString(inbox.resolve("notes.txt"), "This is a text file, not markdown.");

        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);
        scheduler.scanAndIndex();

        verifyNoInteractions(fileIndexer, repository);
    }

    @Test
    void scanAndIndex_multipleFiles_processesAll() throws Exception {
        Files.writeString(inbox.resolve("adr-001.md"), "ADR content for architecture decision one.");
        Files.writeString(inbox.resolve("adr-002.md"), "ADR content for architecture decision two.");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());

        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);
        scheduler.scanAndIndex();

        verify(fileIndexer, times(2)).indexFile(anyString());
    }

    @Test
    void scanAndIndex_fileIndexerThrows_continuesWithRemainingFiles() throws Exception {
        Files.writeString(inbox.resolve("bad.md"), "Problematic file content here.");
        Files.writeString(inbox.resolve("good.md"), "Good file content that indexes fine.");
        when(repository.findByFilePath(anyString())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("embed failed"))
                .doReturn(new FileIndexer.IndexResult(1, "indexed", "good.md"))
                .when(fileIndexer).indexFile(anyString());

        IndexScheduler scheduler = new IndexScheduler(inbox.toString(), fileIndexer, repository);

        // Should not throw, should process both files
        scheduler.scanAndIndex();

        verify(fileIndexer, times(2)).indexFile(anyString());
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
