package ru.andreyz.ragservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.andreyz.ragservice.client.OpenSearchClient;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
public class RagDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);

    private final IndexedDocumentRepository repository;
    private final FileIndexer fileIndexer;
    private final OpenSearchClient openSearchClient;

    public RagDocumentService(IndexedDocumentRepository repository, FileIndexer fileIndexer,
                              OpenSearchClient openSearchClient) {
        this.repository = repository;
        this.fileIndexer = fileIndexer;
        this.openSearchClient = openSearchClient;
    }

    public List<RagDocumentSummary> listDocuments(String type) {
        List<IndexedDocument> documents = type == null || type.isBlank()
                ? repository.findAll()
                : repository.findByDocType(type.trim().toUpperCase());
        return documents.stream()
                .map(this::toSummary)
                .toList();
    }

    public RagDocumentDetails getDocument(Long id) throws IOException {
        IndexedDocument document = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
        try {
            String content = Files.readString(Path.of(document.filePath()));
            ParsedDocument parsed = parse(content);
            return new RagDocumentDetails(
                    summary(document, parsed),
                    content
            );
        } catch (IOException e) {
            log.error("", e);
            return unavailableDetails(document, e);
        }
    }

    public RagDocumentDetails updateDocument(Long id, String content) throws IOException {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        IndexedDocument document = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
        Files.writeString(Path.of(document.filePath()), content);

        ParsedDocument parsed = parse(content);
        repository.save(document.withStatus("outdated", null));

        return new RagDocumentDetails(
                summary(repository.findById(id).orElseThrow(), parsed),
                content
        );
    }

    public FileIndexer.IndexResult reindexDocument(Long id) throws IOException {
        IndexedDocument document = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));
        return fileIndexer.indexFile(document.filePath());
    }

    public DeleteResult deleteDocument(Long id) throws IOException {
        IndexedDocument document = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Document not found: " + id));

        openSearchClient.deleteBySource(document.filePath());

        Path path = Path.of(document.filePath());
        boolean fileDeleted = Files.deleteIfExists(path);
        repository.deleteById(id);

        return new DeleteResult(id, document.filePath(), fileDeleted, "deleted");
    }

    private RagDocumentSummary toSummary(IndexedDocument document) {
        try {
            ParsedDocument parsed = parse(Files.readString(Path.of(document.filePath())));
            return summary(document, parsed);
        } catch (IOException e) {
            log.error("", e);
            return unavailableSummary(document, e);
        }
    }

    private RagDocumentDetails unavailableDetails(IndexedDocument document, IOException e) {
        return new RagDocumentDetails(
                unavailableSummary(document, e),
                unavailableContent(e)
        );
    }

    private RagDocumentSummary unavailableSummary(IndexedDocument document, IOException e) {
        String status = e instanceof NoSuchFileException ? "missing" : document.status();
        return new RagDocumentSummary(
                document.id(),
                document.filePath(),
                Path.of(document.filePath()).getFileName().toString(),
                document.docType(),
                status,
                errorMessage(document, e),
                document.indexedAt(),
                null,
                null,
                null,
                null,
                null
        );
    }

    private String unavailableContent(IOException e) {
        return """
                <!-- unavailable -->

                Document content is unavailable.
                """;
    }

    private String errorMessage(IndexedDocument document, IOException e) {
        String base = Optional.ofNullable(document.errorMessage()).filter(s -> !s.isBlank()).orElse(null);
        String message = e instanceof NoSuchFileException
                ? "Document file is missing on disk"
                : Optional.ofNullable(e.getMessage()).orElse(e.getClass().getSimpleName());
        return base != null ? base + "; " + message : message;
    }

    private RagDocumentSummary summary(IndexedDocument document, ParsedDocument parsed) {
        return new RagDocumentSummary(
                document.id(),
                document.filePath(),
                Path.of(document.filePath()).getFileName().toString(),
                document.docType(),
                document.status(),
                document.errorMessage(),
                document.indexedAt(),
                parsed.frontmatter().get("updated"),
                parsed.title(),
                parsed.frontmatter().get("sender"),
                parsed.frontmatter().get("subject"),
                parsed.frontmatter().get("received_at")
        );
    }

    private ParsedDocument parse(String content) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String block = content.substring(3, end).trim();
                for (String line : block.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String key = line.substring(0, colon).trim();
                        String value = line.substring(colon + 1).trim();
                        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                            value = value.substring(1, value.length() - 1);
                        }
                        frontmatter.put(key, value);
                    }
                }
            }
        }

        String title = null;
        for (String line : content.split("\n")) {
            if (line.startsWith("# ")) {
                title = line.substring(2).trim();
                break;
            }
        }
        if (title == null || title.isBlank()) {
            title = frontmatter.get("subject");
        }
        return new ParsedDocument(frontmatter, title);
    }

    private record ParsedDocument(Map<String, String> frontmatter, String title) {}

    public record RagDocumentSummary(
            Long id,
            String filePath,
            String fileName,
            String docType,
            String status,
            String errorMessage,
            LocalDateTime indexedAt,
            String updated,
            String title,
            String sender,
            String subject,
            String receivedAt
    ) {}

    public record RagDocumentDetails(
            RagDocumentSummary summary,
            String content
    ) {}

    public record DeleteResult(
            Long id,
            String filePath,
            boolean fileDeleted,
            String status
    ) {}
}
