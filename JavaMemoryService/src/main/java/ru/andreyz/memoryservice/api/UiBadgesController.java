package ru.andreyz.memoryservice.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.andreyz.memoryservice.dto.UiBadgesResponse;
import ru.andreyz.memoryservice.service.IntakeService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.OffsetDateTime;

@Slf4j
@RestController
@RequestMapping("/api/ui")
public class UiBadgesController {

    private final TaskService taskService;
    private final IntakeService intakeService;

    public UiBadgesController(TaskService taskService, IntakeService intakeService) {
        this.taskService = taskService;
        this.intakeService = intakeService;
    }

    @GetMapping("/badges")
    public ResponseEntity<UiBadgesResponse> badges() {
        UiBadgesResponse response = new UiBadgesResponse(
                new UiBadgesResponse.Counts(
                        intakeService.countNew(),
                        taskService.findPending().size()),
                OffsetDateTime.now());
        log.debug("UI badges poll: {}", response);
        return ResponseEntity.ok(response);
    }
}
