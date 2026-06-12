package ru.andreyz.ragservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.ragservice.db.IndexedDocument;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;
import ru.andreyz.ragservice.indexer.FileIndexer;
import ru.andreyz.ragservice.mcp.RagMcpTools;
import ru.andreyz.ragservice.search.RagSearchService;
import ru.andreyz.ragservice.search.SearchResult;

import java.io.IOException;
import java.util.List;

@RestController
public class RagRestController {

    private final FileIndexer fileIndexer;
    private final RagMcpTools ragMcpTools;
    private final RagSearchService searchService;
    private final IndexedDocumentRepository repository;

    public RagRestController(FileIndexer fileIndexer, RagMcpTools ragMcpTools,
                             RagSearchService searchService, IndexedDocumentRepository repository) {
        this.fileIndexer = fileIndexer;
        this.ragMcpTools = ragMcpTools;
        this.searchService = searchService;
        this.repository = repository;
    }

    @PostMapping("/api/rag/index")
    public ResponseEntity<FileIndexer.IndexResult> index(@RequestBody IndexRequest req) {
        try {
            return ResponseEntity.ok(fileIndexer.indexFile(req.file_path()));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(new FileIndexer.IndexResult(0, "error: " + e.getMessage(), req.file_path()));
        }
    }

    @PostMapping("/api/rag/index-directory")
    public ResponseEntity<RagMcpTools.DirectoryIndexResult> indexDirectory(@RequestBody IndexDirRequest req) {
        return ResponseEntity.ok(ragMcpTools.ragIndexDirectory(req.dir_path(), req.pattern()));
    }

    @PostMapping("/api/search")
    public ResponseEntity<List<SearchResult>> search(@RequestBody SearchRequest req) {
        int topK = req.top_k() != null ? req.top_k() : 5;
        return ResponseEntity.ok(searchService.search(req.query(), topK));
    }

    @GetMapping("/api/rag/status")
    public ResponseEntity<List<IndexedDocument>> status() {
        return ResponseEntity.ok(repository.findAll());
    }

    record IndexRequest(String file_path) {}
    record IndexDirRequest(String dir_path, String pattern) {}
    record SearchRequest(String query, Integer top_k) {}
}
