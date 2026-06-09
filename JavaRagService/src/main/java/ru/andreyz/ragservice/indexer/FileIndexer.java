package ru.andreyz.ragservice.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Component
public class FileIndexer {

    private static final Logger log = LoggerFactory.getLogger(FileIndexer.class);

    private final ChunkSplitter splitter;
    private final OllamaClient ollama;
    private final OpenSearchClient openSearch;
    private final IndexedDocumentRepository repository;

    public FileIndexer(ChunkSplitter splitter, OllamaClient ollama,
                       OpenSearchClient openSearch, IndexedDocumentRepository repository) {
        this.splitter = splitter;
        this.ollama = ollama;
        this.openSearch = openSearch;
        this.repository = repository;
    }

    public IndexResult indexFile(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }
        String content = Files.readString(path);
        String hash = sha256(content);

        Optional<IndexedDocument> existing = repository.findByFilePath(filePath);
        if (existing.isPresent() && existing.get().fileHash().equals(hash)) {
            return new IndexResult(existing.get().chunkCount(), "skipped", filePath);
        }

        // Remove old chunks before re-indexing
        if (existing.isPresent()) {
            openSearch.deleteBySource(filePath);
        }

        List<String> chunks = splitter.split(content);
        String docId = path.getFileName().toString().replaceAll("\\.md$", "");

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            float[] vector = ollama.embed(chunk);
            String chunkId = docId + "_" + i;
            openSearch.indexDocument(chunkId, chunk, vector, filePath, docId, i);
        }
        int indexed = chunks.size();

        IndexedDocument doc = existing
                .map(d -> d.withUpdated(hash, indexed, "indexed"))
                .orElse(IndexedDocument.create(filePath, hash, indexed));
        repository.save(doc);

        log.info("Indexed {} chunks from {}", indexed, filePath);
        return new IndexResult(indexed, "indexed", filePath);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes());
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record IndexResult(int chunksAdded, String status, String filePath) {}
}
