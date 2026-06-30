package ru.andreyz.memoryservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.Capture;
import ru.andreyz.memoryservice.domain.UsageEventType;
import ru.andreyz.memoryservice.dto.CaptureRequest;
import ru.andreyz.memoryservice.dto.UsageEventCommand;
import ru.andreyz.memoryservice.repository.CaptureRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CaptureService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH-mm-ss");
    private static final DateTimeFormatter FRONT_MATTER_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final CaptureRepository captureRepository;
    private final UsageEventService usageEventService;
    private final Path inboxDir;

    public CaptureService(CaptureRepository captureRepository,
                          UsageEventService usageEventService,
                          @Value("${app.capture.inbox-dir:capture-inbox}") String inboxDir) {
        this.captureRepository = captureRepository;
        this.usageEventService = usageEventService;
        this.inboxDir = Path.of(inboxDir);
    }

    public Capture save(CaptureRequest req) {
        return saveWithFile(req).capture();
    }

    public CaptureSaveResult saveWithFile(CaptureRequest req) {
        Instant now = Instant.now();
        String source = req.source() != null ? req.source() : "manual";
        if (req.sourceId() != null && !req.sourceId().isBlank()) {
            var existing = captureRepository.findBySourceAndSourceId(source, req.sourceId());
            if (existing.isPresent()) {
                return new CaptureSaveResult(existing.get(), null);
            }
        }

        // Email captures are already classified by MailAgent — skip re-classification queue
        if ("email".equals(source)) {
            Capture capture = new Capture(null, req.text(), source, req.sourceId(), "PROCESSED",
                    "email", "capture", null, null, null, null, null, null, now, now, now, null);
            Capture saved = captureRepository.save(capture);
            recordCaptureCreated(saved);
            return new CaptureSaveResult(saved, null);
        }

        Capture capture = new Capture(null, req.text(), source, req.sourceId(), "NEW",
                null, null, null, null, null, null, null, null, now, null, now, null);
        Capture saved = captureRepository.save(capture);
        recordCaptureCreated(saved);
        Path file = writeToInbox(saved);

        // Persist the file path so the UI can show the backup location
        String filePath = (file != null && !file.equals(inboxDir)) ? file.toString() : null;
        if (filePath != null) {
            saved = captureRepository.save(withFilePath(saved, filePath));
        }

        return new CaptureSaveResult(saved, file);
    }

    public Optional<Capture> findById(Long id) {
        return captureRepository.findById(id);
    }

    public List<Capture> findToday() {
        return captureRepository.findByDay(startOfToday(), startOfTomorrow());
    }

    public List<Capture> findTodayNew() {
        return captureRepository.findByStatusAndDay("NEW", startOfToday(), startOfTomorrow());
    }

    public List<Capture> findTodayPending() {
        return findTodayNew();
    }

    public List<Capture> findRecent() {
        return captureRepository.findRecent();
    }

    public List<Capture> findWithFilters(String status, LocalDate date, String source,
                                         String route, String q) {
        List<Capture> candidates;
        if (status != null && !status.isBlank()) {
            candidates = captureRepository.findByStatus(status);
        } else {
            candidates = captureRepository.findAllOrdered();
        }

        Stream<Capture> stream = candidates.stream();

        if (date != null) {
            stream = stream.filter(c -> isOnDate(c.capturedAt(), date));
        }
        if (source != null && !source.isBlank()) {
            stream = stream.filter(c -> source.equalsIgnoreCase(c.source()));
        }
        if (route != null && !route.isBlank()) {
            stream = stream.filter(c -> route.equalsIgnoreCase(c.classified())
                    || route.equalsIgnoreCase(c.route()));
        }
        if (q != null && !q.isBlank()) {
            String qLower = q.toLowerCase();
            stream = stream.filter(c -> c.rawText() != null
                    && c.rawText().toLowerCase().contains(qLower));
        }

        return stream
                .sorted(Comparator.comparing(Capture::capturedAt).reversed())
                .limit(200)
                .toList();
    }

    public Capture markProcessing(Long id) {
        Capture c = requireById(id);
        return captureRepository.save(transition(c, "PROCESSING", c.classified(), c.routedTo(),
                null, c.capturedAt(), null));
    }

    public Capture markProcessed(Long id, String classified, String routedTo) {
        Capture c = requireById(id);
        return captureRepository.save(transition(c, "PROCESSED", classified, routedTo,
                null, c.capturedAt(), Instant.now()));
    }

    public Capture markError(Long id, String errorMessage) {
        Capture c = requireById(id);
        return captureRepository.save(transition(c, "ERROR", c.classified(), c.routedTo(),
                errorMessage, c.capturedAt(), c.processedAt()));
    }

    public Capture archive(Long id) {
        Capture c = requireById(id);
        Instant now = Instant.now();
        Capture archived = new Capture(c.id(), c.rawText(), c.source(), c.sourceId(),
                "ARCHIVED", c.classified(), c.routedTo(), c.route(), c.targetType(),
                c.targetId(), c.targetRef(), c.filePath(), c.errorMessage(),
                c.capturedAt(), c.processedAt(), now, now);
        return captureRepository.save(archived);
    }

    public Capture reprocess(Long id) {
        Capture c = requireById(id);
        Capture reset = new Capture(c.id(), c.rawText(), c.source(), c.sourceId(),
                "NEW", null, null, null, null, null, null, c.filePath(), null,
                c.capturedAt(), null, Instant.now(), null);
        return captureRepository.save(reset);
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
            log.error("", e);
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
            log.error("", e);
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
            log.error("", e);
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

    private Capture transition(Capture c, String status, String classified, String routedTo,
                               String errorMessage, Instant capturedAt, Instant processedAt) {
        return new Capture(c.id(), c.rawText(), c.source(), c.sourceId(),
                status, classified, routedTo, classified, c.targetType(),
                c.targetId(), routedTo, c.filePath(), errorMessage,
                capturedAt, processedAt, Instant.now(), c.archivedAt());
    }

    private Capture withFilePath(Capture c, String filePath) {
        return new Capture(c.id(), c.rawText(), c.source(), c.sourceId(),
                c.status(), c.classified(), c.routedTo(), c.route(), c.targetType(),
                c.targetId(), c.targetRef(), filePath, c.errorMessage(),
                c.capturedAt(), c.processedAt(), c.updatedAt(), c.archivedAt());
    }

    private Capture requireById(Long id) {
        return captureRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Capture not found: " + id));
    }

    private Instant startOfToday() {
        return LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private Instant startOfTomorrow() {
        return LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    private boolean isOnDate(Instant instant, LocalDate date) {
        if (instant == null) return false;
        return instant.atZone(ZoneId.systemDefault()).toLocalDate().equals(date);
    }

    public record CaptureSaveResult(Capture capture, Path file) {}
    public record CaptureFile(String file, String text) {}

    private void recordCaptureCreated(Capture capture) {
        usageEventService.record(new UsageEventCommand(
                UsageEventType.CAPTURE_CREATED,
                capture.source() != null && !capture.source().isBlank() ? capture.source() : "manual-ui",
                "SUCCESS",
                null,
                "capture",
                String.valueOf(capture.id()),
                null,
                null,
                java.util.Map.of("status", capture.status())
        ));
    }
}
