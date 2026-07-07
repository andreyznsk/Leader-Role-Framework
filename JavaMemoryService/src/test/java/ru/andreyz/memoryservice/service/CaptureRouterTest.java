package ru.andreyz.memoryservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.dto.IntakeCreateRequest;
import ru.andreyz.memoryservice.dto.IntakeItemDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CaptureRouterTest {

    @Mock IntakeService intakeService;

    CaptureRouter router;

    @BeforeEach
    void setUp() {
        router = new CaptureRouter(intakeService, new ObjectMapper());
        when(intakeService.create(any())).thenReturn(new IntakeItemDto(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "CAPTURE",
                "1",
                null,
                null,
                "mock",
                "prompt",
                null,
                null,
                "TASK",
                null,
                null,
                null,
                "NEW",
                BigDecimal.ZERO,
                "capture-bot",
                null,
                Instant.now(),
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void routeTaskCreatesIntakeItem() {
        String routedTo = router.route(
                capture(1L, "TASK", "Write ADR", "details", "HIGH"),
                "raw capture",
                "prompt",
                "[{\"type\":\"TASK\"}]",
                "mock"
        );

        ArgumentCaptor<IntakeCreateRequest> captor = ArgumentCaptor.forClass(IntakeCreateRequest.class);
        verify(intakeService).create(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo("CAPTURE");
        assertThat(captor.getValue().suggestedRoute()).isEqualTo("TASK");
        assertThat(routedTo).isEqualTo("intake/11111111-1111-1111-1111-111111111111");
    }

    @Test
    void routeKnowledgeMapsToRagIntake() {
        router.route(
                capture(6L, "KNOWLEDGE", "Saga pattern", "used for distributed tx", "LOW"),
                "raw capture",
                "prompt",
                "[{\"type\":\"KNOWLEDGE\"}]",
                "mock"
        );

        ArgumentCaptor<IntakeCreateRequest> captor = ArgumentCaptor.forClass(IntakeCreateRequest.class);
        verify(intakeService).create(captor.capture());
        assertThat(captor.getValue().suggestedRoute()).isEqualTo("RAG");
        assertThat(captor.getValue().suggestedPayload().get("docType").asText()).isEqualTo("RAG");
    }

    @Test
    void routeQuestionMapsToNoteIntake() {
        router.route(
                capture(4L, "QUESTION", "How does circuit breaker work?", "context", "NORMAL"),
                "raw capture",
                "prompt",
                "[{\"type\":\"QUESTION\"}]",
                "mock"
        );

        ArgumentCaptor<IntakeCreateRequest> captor = ArgumentCaptor.forClass(IntakeCreateRequest.class);
        verify(intakeService).create(captor.capture());
        assertThat(captor.getValue().suggestedRoute()).isEqualTo("NOTE");
        assertThat(captor.getValue().suggestedPayload().get("tags").asText()).contains("question");
    }

    private ClassifiedCapture capture(Long id, String type, String title, String body, String priority) {
        return new ClassifiedCapture(id, null, type, title, body, null, priority);
    }
}
