package ru.andreyz.memoryservice.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.dto.UsageEventRequest;
import ru.andreyz.memoryservice.service.UsageEventService;

@RestController
@Profile("local")
@RequestMapping("/api/stats")
public class UsageEventDebugController {

    private final UsageEventService usageEventService;

    public UsageEventDebugController(UsageEventService usageEventService) {
        this.usageEventService = usageEventService;
    }

    @PostMapping("/events")
    public ResponseEntity<Void> record(@RequestBody UsageEventRequest request) {
        usageEventService.record(new UsageEventCommand(
                request.eventType(),
                request.source(),
                request.status(),
                request.correlationId(),
                request.entityType(),
                request.entityId(),
                request.durationMs(),
                request.savedMinutes(),
                request.metadata()
        ));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
