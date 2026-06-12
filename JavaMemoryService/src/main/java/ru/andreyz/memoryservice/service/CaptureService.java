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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

@Service
public class CaptureService {

    private static final Logger log = LoggerFactory.getLogger(CaptureService.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final DateTimeFormatter FRONT_MATTER_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CaptureRepository captureRepository;
    private final Path inboxDir;

    public CaptureService(CaptureRepository captureRepository,
                          @Value("${app.capture.inbox-dir:capture-inbox}") String inboxDir) {
        this.captureRepository = captureRepository;
        this.inboxDir = Path.of(inboxDir);
    }

    public Capture save(CaptureRequest req) {
        return saveWithFile(req).capture();
    }

    public CaptureSaveResult saveWithFile(CaptureRequest req) {
        Instant now = Instant.now();
        String source = req.source() != null ? req.source() : "manual";
        Capture capture = new Capture(null, req.text(), source, "PENDING",
                null, null, now, null);
        Capture saved = captureRepository.save(capture);
        Path file = writeToInbox(saved);
        return new CaptureSaveResult(saved, file);
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

    public List<CaptureFile> findTodayFiles() {
        LocalDate today = LocalDate.now();
        Path dayDir = inboxDir.resolve(today.toString());
        if (!Files.isDirectory(dayDir)) {
            return List.of();
        }

        try (var stream = Files.list(dayDir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::readCaptureFile)
                    .flatMap(java.util.Optional::stream)
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list capture inbox {}: {}", dayDir, e.getMessage());
            return List.of();
        }
    }

    public Path moveToProcessed(String filename) throws IOException {
        LocalDate today = LocalDate.now();
        Path source = inboxDir.resolve(today.toString()).resolve(filename).normalize();
        Path sourceParent = inboxDir.resolve(today.toString()).normalize();
        if (!source.startsWith(sourceParent) || !Files.isRegularFile(source)) {
            throw new IOException("Capture file not found: " + filename);
        }

        Path processedDir = inboxDir.resolve("processed").resolve(today.toString());
        Files.createDirectories(processedDir);
        Path target = uniquePath(processedDir, filename);
        return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private java.util.Optional<CaptureFile> readCaptureFile(Path path) {
        try {
            String content = Files.readString(path);
            String text = content;
            if (content.startsWith("---")) {
                int end = content.indexOf("\n---", 3);
                if (end >= 0) {
                    text = content.substring(end + 4).stripLeading();
                }
            }
            return java.util.Optional.of(new CaptureFile(path.getFileName().toString(), text.stripTrailing()));
        } catch (IOException e) {
            log.warn("Failed to read capture file {}: {}", path, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    private Path writeToInbox(Capture capture) {
        try {
            LocalDateTime capturedAt = LocalDateTime.ofInstant(capture.capturedAt(), ZoneId.systemDefault());
            LocalDate date = capturedAt.toLocalDate();
            LocalTime time = capturedAt.toLocalTime();
            Path dayDir = inboxDir.resolve(date.toString());
            Files.createDirectories(dayDir);

            String filename = time.format(TIME_FMT) + ".md";
            Path file = uniquePath(dayDir, filename);
            String content = "---\n" +
                    "date: " + capturedAt.format(FRONT_MATTER_FMT) + "\n" +
                    "source: " + capture.source() + "\n" +
                    "---\n" +
                    capture.rawText() + "\n";

            Files.writeString(file, content, StandardOpenOption.CREATE_NEW);
            return file;
        } catch (IOException e) {
            log.warn("Failed to write capture {} to inbox: {}", capture.id(), e.getMessage());
            return inboxDir;
        }
    }

    private Path uniquePath(Path dir, String filename) throws IOException {
        Path candidate = dir.resolve(filename);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = filename;
        String ext = "";
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) {
            base = filename.substring(0, dot);
            ext = filename.substring(dot);
        }
        for (int i = 1; ; i++) {
            candidate = dir.resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    private Instant startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private Instant startOfTomorrow() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    public record CaptureSaveResult(Capture capture, Path file) {}
    public record CaptureFile(String file, String text) {}
}
