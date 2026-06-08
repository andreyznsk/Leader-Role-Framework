package ru.andreyz.memoryservice.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.ContextDto;
import ru.andreyz.memoryservice.service.ContextService;

@RestController
@RequestMapping("/api/context")
public class ContextController {

    private final ContextService contextService;

    public ContextController(ContextService contextService) {
        this.contextService = contextService;
    }

    @GetMapping
    public ResponseEntity<ContextDto> getContext() {
        return ResponseEntity.ok(contextService.buildContext());
    }
}
