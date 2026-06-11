package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.CaptureRequest;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
class CaptureProcessingServiceTest {

    @Autowired
    private CaptureProcessingService processingService;

    @Autowired
    private CaptureService captureService;

    @MockBean
    private CaptureClassifierAgent classifierAgent;

    @Test
    void processToday_noPending_returnsZeroWithoutCallingAgent() throws Exception {
        // Ensure any previously created captures in the test DB are processed
        captureService.findTodayPending()
                .forEach(c -> captureService.markProcessed(c.id(), "TASK", "tasks/pending"));

        CaptureProcessingService.ProcessResult result = processingService.processToday();

        assertThat(result.total()).isEqualTo(0);
        assertThat(result.routed()).isEqualTo(0);
    }

    @Test
    void processToday_classifiesAndRoutesCaptures() throws Exception {
        Capture saved = captureService.save(new CaptureRequest("needs classification", "cli"));

        when(classifierAgent.classify(anyList()))
                .thenReturn(List.of(new ClassifiedCapture(
                        saved.id(), "QUESTION", "Classification question", "context", "NORMAL")));

        CaptureProcessingService.ProcessResult result = processingService.processToday();

        assertThat(result.total()).isGreaterThanOrEqualTo(1);
        assertThat(result.routed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processToday_marksRoutedCapturesAsProcessed() throws Exception {
        Capture saved = captureService.save(new CaptureRequest("mark processed test", "cli"));

        when(classifierAgent.classify(anyList()))
                .thenReturn(List.of(new ClassifiedCapture(
                        saved.id(), "QUESTION", "Q title", "Q body", "LOW")));

        processingService.processToday();

        List<Capture> today = captureService.findToday();
        Capture updated = today.stream()
                .filter(c -> c.id().equals(saved.id()))
                .findFirst()
                .orElseThrow();

        assertThat(updated.status()).isEqualTo("PROCESSED");
        assertThat(updated.classified()).isEqualTo("QUESTION");
    }

    @Test
    void processToday_classifierThrows_returnsZeroRouted() throws Exception {
        captureService.save(new CaptureRequest("will fail classification", "cli"));

        when(classifierAgent.classify(anyList()))
                .thenThrow(new IOException("claude not found"));

        CaptureProcessingService.ProcessResult result = processingService.processToday();

        assertThat(result.total()).isGreaterThanOrEqualTo(1);
        assertThat(result.routed()).isEqualTo(0);
    }

    @Test
    void processToday_passesTodayPendingCaptureToAgent() throws Exception {
        Capture saved = captureService.save(new CaptureRequest("agent receives this", "cli"));

        when(classifierAgent.classify(anyList())).thenReturn(List.of());

        processingService.processToday();

        verify(classifierAgent).classify(
                org.mockito.ArgumentMatchers.argThat(list ->
                        list.stream().anyMatch(c -> c.id().equals(saved.id()))
                )
        );
    }
}
