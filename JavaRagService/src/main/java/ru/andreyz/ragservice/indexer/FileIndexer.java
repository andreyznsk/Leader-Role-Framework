package ru.andreyz.ragservice.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.client.OllamaClient;
import ru.andreyz.ragservice.client.OpenSearchClient;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.validation.DocumentValidator;
import ru.andreyz.ragservice.validation.ValidationResult;

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
    private final DocumentValidator validator;

    public FileIndexer(ChunkSplitter splitter, OllamaClient ollama,
                       OpenSearchClient openSearch, IndexedDocumentRepository repository,
                       DocumentValidator validator) {
        this.splitter = splitter;
        this.ollama = ollama;
        this.openSearch = openSearch;
        this.repository = repository;
        this.validator = validator;
    }

    public IndexResult indexFile(String filePath) throws IOException {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + filePath);
        }
        String content = Files.readString(path);
        String hash = sha256(content);

        ValidationResult validation = validator.validate(content);
        String docType = validation.docType() != null ? validation.docType().name() : null;
        if (!validation.valid()) {
            Optional<IndexedDocument> existing = repository.findByFilePath(filePath);
            IndexedDocument doc = existing
                    .map(d -> d.withUpdated(docType != null ? docType : d.docType(), hash, 0, "invalid", validation.errorsAsString()))
                    .orElse(IndexedDocument.invalid(filePath, docType, hash, validation.errorsAsString()));
            repository.save(doc);
            log.warn("⚠️  File failed validation, skipping: {} — {}", filePath, validation.errorsAsString());
            return new IndexResult(0, "invalid", filePath);
        }

        Optional<IndexedDocument> existing = repository.findByFilePath(filePath);
        if (existing.isPresent()
                && existing.get().fileHash().equals(hash)
                && "indexed".equals(existing.get().status())) {
            return new IndexResult(existing.get().chunkCount(), "skipped", filePath);
        }

        if (existing.isPresent()) {
            openSearch.deleteBySource(filePath);
        }

        List<String> chunks = splitter.split(content);
        String docId = path.getFileName().toString().replaceAll("\\.md$", "");
        String action = existing.isPresent() ? "reindex" : "new";

        log.info("[INDEX] → file={} docId={} type={} size={}B chunks={} hash={} action={}",
                filePath, docId, validation.docType(), content.length(),
                chunks.size(), hash.substring(0, 8), action);

        try {
            for (int i = 0; i < chunks.size(); i++) {
                String chunk = chunks.get(i);
                float[] vector = ollama.embed(chunk);
                openSearch.indexDocument(docId + "_" + i, chunk, vector, filePath, docId, i);
            }
            int indexed = chunks.size();

            IndexedDocument doc = existing
                    .map(d -> d.withUpdated(docType, hash, indexed, "indexed"))
                    .orElse(IndexedDocument.indexed(filePath, docType, hash, indexed));
            repository.save(doc);

            log.info("[INDEX] ✓ file={} docId={} type={} chunks={} hash={} action={}",
                    filePath, docId, validation.docType(), indexed, hash.substring(0, 8), action);
            return new IndexResult(indexed, "indexed", filePath);

        } catch (Exception e) {
            String errMsg = e.getMessage();
            IndexedDocument doc = existing
                    .map(d -> d.withUpdated(docType, hash, 0, "failed", errMsg))
                    .orElse(IndexedDocument.failed(filePath, docType, hash, errMsg));
            repository.save(doc);
            log.error("❌ Indexing error: {} — {}", filePath, errMsg);
            return new IndexResult(0, "failed: " + errMsg, filePath);
        }
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
