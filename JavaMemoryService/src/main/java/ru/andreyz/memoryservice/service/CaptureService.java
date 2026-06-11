package ru.andreyz.memoryservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.dto.CaptureRequest;
import ru.andreyz.memoryservice.repository.CaptureRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH-mm-ss");

    private final CaptureRepository captureRepository;
    private final Path inboxDir;

    public CaptureService(CaptureRepository captureRepository,
                          @Value("${app.capture.inbox-dir:capture-inbox}") String inboxDir) {
        this.captureRepository = captureRepository;
        this.inboxDir = Path.of(inboxDir);
    }

    public Capture save(CaptureRequest req) {
        Instant now = Instant.now();
        String source = req.source() != null ? req.source() : "cli";
        Capture capture = new Capture(null, req.text(), source, "PENDING",
                null, null, now, null);
        Capture saved = captureRepository.save(capture);
        writeToInbox(saved);
        return saved;
    }

    public List<Capture> findToday() {
        return captureRepository.findByDay(startOfToday(), startOfTomorrow());
    }

    public List<Capture> findTodayPending() {
        return captureRepository.findByStatusAndDay("PENDING", startOfToday(), startOfTomorrow());
    }

    public List<Capture> findRecent() {
        return captureRepository.findRecent();
    }

    public Capture markProcessed(Long id, String classified, String routedTo) {
        Capture c = captureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Capture not found: " + id));
        Capture updated = new Capture(c.id(), c.rawText(), c.source(), "PROCESSED",
                classified, routedTo, c.capturedAt(), Instant.now());
        return captureRepository.save(updated);
    }

    private void writeToInbox(Capture capture) {
        try {
            LocalDate date = capture.capturedAt().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalTime time = capture.capturedAt().atZone(ZoneId.systemDefault()).toLocalTime();
            Path dayDir = inboxDir.resolve(date.toString());
            Files.createDirectories(dayDir);

            String filename = time.format(TIME_FMT) + "-" + capture.id() + ".md";
            String content = "# Capture " + date + " " + time.withNano(0) + "\n" +
                    "source: " + capture.source() + "\n" +
                    "---\n" +
                    capture.rawText() + "\n";

            Files.writeString(dayDir.resolve(filename), content, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            log.warn("Failed to write capture {} to inbox: {}", capture.id(), e.getMessage());
        }
    }

    private Instant startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private Instant startOfTomorrow() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }
}
