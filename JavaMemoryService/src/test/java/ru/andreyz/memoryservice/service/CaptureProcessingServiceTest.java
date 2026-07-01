package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.common.agent.AgentException;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.CaptureRequest;
import ru.andreyz.memoryservice.dto.ClassifiedCapture;
import ru.andreyz.memoryservice.service.CaptureClassifierAgent.ClassificationBatch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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

    @Value("${app.capture.inbox-dir}")
    private String inboxDir;

    @BeforeEach
    void cleanInbox() throws IOException {
        Path root = Path.of(inboxDir);
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                        .filter(path -> !path.equals(root))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        }
    }

    @Test
    void processToday_noPending_returnsZeroWithoutCallingAgent() throws Exception {
        CaptureProcessingService.ProcessResult result = processingService.processToday();

        assertThat(result.total()).isEqualTo(0);
        assertThat(result.routed()).isEqualTo(0);
    }

    @Test
    void processToday_classifiesAndRoutesCaptures() throws Exception {
        Capture saved = captureService.save(new CaptureRequest("needs classification", "cli"));
        String file = captureService.findTodayFiles().get(0).file();

        when(classifierAgent.classifyFilesWithTrace(anyList(), anyString()))
                .thenReturn(batchOf(new ClassifiedCapture(
                        saved.id(), file, "NOTE", "Classification note", "context", "capture", "NORMAL")));

        CaptureProcessingService.ProcessResult result = processingService.processToday();

        assertThat(result.total()).isGreaterThanOrEqualTo(1);
        assertThat(result.routed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processToday_marksRoutedCapturesAsProcessed() throws Exception {
        Capture saved = captureService.save(new CaptureRequest("mark processed test", "cli"));
        String file = captureService.findTodayFiles().get(0).file();

        when(classifierAgent.classifyFilesWithTrace(anyList(), anyString()))
                .thenReturn(batchOf(new ClassifiedCapture(
                        saved.id(), file, "NOTE", "Q title", "Q body", "capture", "LOW")));

        processingService.processToday();

        List<Capture> today = captureService.findToday();
        Capture updated = today.stream()
                .filter(c -> c.id().equals(saved.id()))
                .findFirst()
                .orElseThrow();

        assertThat(updated.status()).isEqualTo("PROCESSED");
        assertThat(updated.classified()).isEqualTo("NOTE");
    }

    @Test
    void processToday_movesRoutedFileToProcessed() throws Exception {
        Capture saved = captureService.save(new CaptureRequest("move processed test", "cli"));
        String file = captureService.findTodayFiles().get(0).file();

        when(classifierAgent.classifyFilesWithTrace(anyList(), anyString()))
                .thenReturn(batchOf(new ClassifiedCapture(
                        saved.id(), file, "NOTE", "Moved", "move processed test", "capture", "NORMAL")));

        processingService.processToday();

        Path processed = Path.of(inboxDir)
                .resolve("processed")
                .resolve(java.time.LocalDate.now().toString())
                .resolve(file);
        assertThat(processed).exists();
    }

    @Test
    void processToday_classifierThrows_returnsZeroRouted() throws Exception {
        captureService.save(new CaptureRequest("will fail classification", "cli"));

        when(classifierAgent.classifyFilesWithTrace(anyList(), anyString()))
                .thenThrow(new AgentException("claude not found"));

        CaptureProcessingService.ProcessResult result = processingService.processToday();

        assertThat(result.total()).isGreaterThanOrEqualTo(1);
        assertThat(result.routed()).isEqualTo(0);
    }

    @Test
    void processToday_passesTodayPendingCaptureToAgent() throws Exception {
        captureService.save(new CaptureRequest("agent receives this", "cli"));

        when(classifierAgent.classifyFilesWithTrace(anyList(), anyString())).thenReturn(batchOf());

        processingService.processToday();

        verify(classifierAgent).classifyFilesWithTrace(
                org.mockito.ArgumentMatchers.argThat(list ->
                        list.stream().anyMatch(c -> c.text().equals("agent receives this"))
                ),
                anyString()
        );
    }

    private ClassificationBatch batchOf(ClassifiedCapture... items) {
        return new ClassificationBatch(List.of(items), "test-prompt", "[]", "test");
    }
}
