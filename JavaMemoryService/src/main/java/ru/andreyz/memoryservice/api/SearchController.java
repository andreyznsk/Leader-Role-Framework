package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.memoryservice.search.*;

import java.util.List;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final GlobalSearchService searchService;

    public SearchController(GlobalSearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public ResponseEntity<SearchResponse> search(@RequestBody SearchRequest request) {
        if (request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(searchService.search(request));
    }

    @GetMapping("/layers")
    public ResponseEntity<List<LayerInfo>> layers() {
        return ResponseEntity.ok(searchService.getLayers());
    }
}
