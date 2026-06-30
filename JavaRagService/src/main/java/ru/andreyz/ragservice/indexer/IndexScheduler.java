package ru.andreyz.ragservice.indexer;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.control.RagRuntimeConfig;
import ru.andreyz.ragservice.control.RagRuntimeConfigService;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Slf4j
@RequiredArgsConstructor
@Component
public class IndexScheduler {


    private final FileIndexer fileIndexer;
    private final IndexedDocumentRepository repository;
    private final RagRuntimeConfigService runtimeConfigService;
    private LocalDateTime lastScanFinishedAt;

    @Scheduled(fixedDelay = 5000)
    public void scanAndIndex() {
        if (!runtimeConfigService.shouldScan(LocalDateTime.now(), lastScanFinishedAt)) {
            return;
        }

        RagRuntimeConfig runtime = runtimeConfigService.snapshot();
        Path inbox = Path.of(runtime.ragInboxPath());
        if (!Files.isDirectory(inbox)) {
            log.debug("rag-inbox directory does not exist yet: {}", inbox.toAbsolutePath());
            return;
        }

        List<Path> mdFiles;
        try (Stream<Path> stream = Files.walk(inbox)) {
            mdFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md"))
                    .toList();
        } catch (IOException e) {
            log.error("Failed to scan rag-inbox: {}", e.getMessage());
            log.error("", e);
            runtimeConfigService.registerScanResult("Scan failed: " + e.getMessage());
            return;
        }

        int indexed = 0;
        int skipped = 0;
        int invalid = 0;
        int failed = 0;
        int disabled = 0;
        for (Path file : mdFiles) {
            String filePath = file.toString();
            try {
                String content = Files.readString(file);
                String hash = sha256(content);
                boolean changed = repository.findByFilePath(filePath)
                        .map(d -> !d.fileHash().equals(hash))
                        .orElse(true);

                if (changed) {
                    log.info("Indexing new/changed file: {}", filePath);
                    FileIndexer.IndexResult result = fileIndexer.indexFile(filePath);
                    switch (result.status()) {
                        case "indexed" -> indexed++;
                        case "skipped" -> skipped++;
                        case "invalid" -> invalid++;
                        case "disabled" -> disabled++;
                        default -> failed++;
                    }
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Failed to process {}: {}", filePath, e.getMessage());
                log.error("", e);
                failed++;
            }
        }

        runtimeConfigService.registerScanResult("scan finished: indexed=%d skipped=%d invalid=%d failed=%d disabled=%d"
                .formatted(indexed, skipped, invalid, failed, disabled));
        lastScanFinishedAt = LocalDateTime.now();
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes());
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
