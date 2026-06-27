package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.service.KnowledgeService;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeService knowledgeService;

    public KnowledgeDocumentController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeService.KnowledgeDocumentSummary>> list(@RequestParam(required = false) String type) {
        return ResponseEntity.ok(knowledgeService.list(type));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeService.KnowledgeDocumentDetails> get(@PathVariable Long id) {
        return ResponseEntity.ok(knowledgeService.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeService.KnowledgeDocumentDetails> update(@PathVariable Long id,
                                                                            @RequestBody UpdateKnowledgeDocumentRequest request) {
        return ResponseEntity.ok(knowledgeService.update(id, request.content()));
    }

    @PostMapping("/{id}/reindex")
    public ResponseEntity<KnowledgeService.ReindexResult> reindex(@PathVariable Long id) {
        return ResponseEntity.ok(knowledgeService.reindex(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<KnowledgeService.DeleteResult> delete(@PathVariable Long id) {
        return ResponseEntity.ok(knowledgeService.delete(id));
    }

    public record UpdateKnowledgeDocumentRequest(String content) {}
}
