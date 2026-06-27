package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.service.KnowledgeService;

import java.util.List;

@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final KnowledgeService knowledgeService;

    public NoticeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public ResponseEntity<List<KnowledgeService.KnowledgeDocumentSummary>> list() {
        return ResponseEntity.ok(knowledgeService.list("NOTICE"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeService.KnowledgeDocumentDetails> get(@PathVariable Long id) {
        return ResponseEntity.ok(knowledgeService.get(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KnowledgeService.KnowledgeDocumentDetails> update(@PathVariable Long id,
                                                                            @RequestBody KnowledgeDocumentController.UpdateKnowledgeDocumentRequest request) {
        return ResponseEntity.ok(knowledgeService.update(id, request.content()));
    }

    @PostMapping("/{id}/reindex")
    public ResponseEntity<KnowledgeService.ReindexResult> reindex(@PathVariable Long id) {
        return ResponseEntity.ok(knowledgeService.reindex(id));
    }
}
