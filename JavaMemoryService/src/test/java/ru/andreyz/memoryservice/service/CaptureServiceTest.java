package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.CaptureRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class CaptureServiceTest {

    @Autowired
    private CaptureService captureService;

    @Value("${app.capture.inbox-dir}")
    private String inboxDirStr;

    @Test
    void save_assignsIdAndPendingStatus() {
        Capture capture = captureService.save(new CaptureRequest("test note", "cli"));

        assertThat(capture.id()).isNotNull();
        assertThat(capture.status()).isEqualTo("PENDING");
        assertThat(capture.rawText()).isEqualTo("test note");
        assertThat(capture.source()).isEqualTo("cli");
    }

    @Test
    void save_defaultsSourceToCli() {
        Capture capture = captureService.save(new CaptureRequest("note without source", null));

        assertThat(capture.source()).isEqualTo("manual");
    }

    @Test
    void save_writesFileToInbox() throws IOException {
        Capture capture = captureService.save(new CaptureRequest("inbox file test", "cli"));

        Path dayDir = Path.of(inboxDirStr).resolve(LocalDate.now().toString());
        assertThat(dayDir).exists();

        boolean fileExists = Files.list(dayDir)
                .anyMatch(p -> p.getFileName().toString().endsWith(".md"));
        assertThat(fileExists).isTrue();
    }

    @Test
    void save_fileContainsTextAndSource() throws IOException {
        Capture capture = captureService.save(new CaptureRequest("my important note", "telegram"));

        Path dayDir = Path.of(inboxDirStr).resolve(LocalDate.now().toString());
        Path file = Files.list(dayDir)
                .filter(p -> {
                    try {
                        return Files.readString(p).contains("my important note");
                    } catch (IOException e) {
                        return false;
                    }
                })
                .findFirst()
                .orElseThrow();

        String content = Files.readString(file);
        assertThat(content)
                .startsWith("---\n")
                .contains("date: ")
                .contains("my important note")
                .contains("source: telegram");
    }

    @Test
    void findTodayFiles_readsRawTextFromInbox() {
        captureService.save(new CaptureRequest("raw text from file", "manual"));

        List<CaptureService.CaptureFile> files = captureService.findTodayFiles();

        assertThat(files).anySatisfy(file -> assertThat(file.text()).isEqualTo("raw text from file"));
    }

    @Test
    void findToday_includesJustSaved() {
        captureService.save(new CaptureRequest("today visibility check", "cli"));

        List<Capture> today = captureService.findToday();

        assertThat(today).anySatisfy(c -> assertThat(c.rawText()).isEqualTo("today visibility check"));
    }

    @Test
    void findTodayPending_excludesProcessedCaptures() {
        Capture saved = captureService.save(new CaptureRequest("will be processed", "cli"));
        captureService.markProcessed(saved.id(), "TASK", "tasks/pending");

        List<Capture> pending = captureService.findTodayPending();

        assertThat(pending).noneMatch(c -> c.id().equals(saved.id()));
    }

    @Test
    void markProcessed_updatesStatusAndFields() {
        Capture saved = captureService.save(new CaptureRequest("to process", "cli"));

        captureService.markProcessed(saved.id(), "RISK", "risks");

        List<Capture> today = captureService.findToday();
        Capture processed = today.stream()
                .filter(c -> c.id().equals(saved.id()))
                .findFirst()
                .orElseThrow();

        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.classified()).isEqualTo("RISK");
        assertThat(processed.routedTo()).isEqualTo("risks");
        assertThat(processed.processedAt()).isNotNull();
    }

    @Test
    void markProcessed_unknownId_throwsException() {
        assertThatThrownBy(() -> captureService.markProcessed(999999L, "TASK", "tasks/pending"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999999");
    }

    @Test
    void findRecent_returnsAtMost20() {
        for (int i = 0; i < 5; i++) {
            captureService.save(new CaptureRequest("recent note " + i, "cli"));
        }

        List<Capture> recent = captureService.findRecent();

        assertThat(recent).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void findRecent_orderedByDateDesc() {
        Capture first = captureService.save(new CaptureRequest("first note", "cli"));
        Capture second = captureService.save(new CaptureRequest("second note", "cli"));

        List<Capture> recent = captureService.findRecent();

        // most recent should come first
        int firstIdx = indexById(recent, first.id());
        int secondIdx = indexById(recent, second.id());
        assertThat(secondIdx).isLessThan(firstIdx);
    }

    private int indexById(List<Capture> list, Long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) return i;
        }
        throw new AssertionError("id " + id + " not found in list");
    }
}
