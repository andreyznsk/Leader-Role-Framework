package ru.andreyz.ragservice.mcp;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer;
import ru.andreyz.ragservice.indexer.FileIndexer.IndexResult;
import ru.andreyz.ragservice.search.RagSearchService;
import ru.andreyz.ragservice.search.SearchResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

@Component
public class RagMcpTools {

    private final FileIndexer fileIndexer;
    private final RagSearchService searchService;
    private final IndexedDocumentRepository repository;

    public RagMcpTools(FileIndexer fileIndexer, RagSearchService searchService,
                       IndexedDocumentRepository repository) {
        this.fileIndexer = fileIndexer;
        this.searchService = searchService;
        this.repository = repository;
    }

    @Tool(description = "Index a single Markdown file into the RAG knowledge base immediately.")
    public IndexResult ragIndex(
            @ToolParam(description = "Absolute or relative path to the .md file") String filePath) {
        try {
            return fileIndexer.indexFile(filePath);
        } catch (IOException e) {
            return new IndexResult(0, "error: " + e.getMessage(), filePath);
        }
    }

    @Tool(description = "Scan a directory and index all new or changed Markdown files. Skips already-indexed unchanged files.")
    public DirectoryIndexResult ragIndexDirectory(
            @ToolParam(description = "Path to the directory to scan") String dirPath,
            @ToolParam(description = "File pattern, default '*.md'", required = false) String pattern) {
        Path dir = Path.of(dirPath);
        if (!Files.isDirectory(dir)) {
            return new DirectoryIndexResult(0, 0, 0, 0, "directory not found: " + dirPath);
        }

        String suffix = (pattern != null && pattern.startsWith("*.")) ? pattern.substring(1) : ".md";
        List<Path> files;
        try (Stream<Path> stream = Files.walk(dir)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix))
                    .toList();
        } catch (IOException e) {
            return new DirectoryIndexResult(0, 0, 0, 0, "scan error: " + e.getMessage());
        }

        int indexed = 0, skipped = 0, failed = 0, invalid = 0;
        for (Path file : files) {
            String fp = file.toString();
            try {
                String content = Files.readString(file);
                String hash = sha256(content);
                boolean changed = repository.findByFilePath(fp)
                        .map(d -> !d.fileHash().equals(hash))
                        .orElse(true);
                if (!changed) {
                    skipped++;
                    continue;
                }
                IndexResult r = fileIndexer.indexFile(fp);
                if ("disabled".equals(r.status())) failed++;
                else if (r.status().startsWith("error")) failed++;
                else if ("invalid".equals(r.status())) invalid++;
                else indexed++;
            } catch (Exception e) {
                failed++;
            }
        }
        return new DirectoryIndexResult(indexed, skipped, failed, invalid, "done");
    }

    @Tool(description = "Semantic search in the RAG knowledge base. Returns top-K most relevant text chunks.")
    public List<SearchResult> ragSearch(
            @ToolParam(description = "Search query in natural language") String query,
            @ToolParam(description = "Number of results to return", required = false) Integer topK) {
        return searchService.search(query, topK);
    }

    @Tool(description = "List all documents indexed in the RAG knowledge base with their status.")
    public List<IndexedDocument> ragStatus() {
        return repository.findAll();
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

    public record DirectoryIndexResult(int indexed, int skipped, int failed, int invalid, String message) {}
}
