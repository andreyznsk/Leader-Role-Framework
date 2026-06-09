package ru.andreyz.ragservice.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.andreyz.ragservice.db.IndexedDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

@Component
public class IndexScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexScheduler.class);

    private final String inboxPath;
    private final FileIndexer fileIndexer;
    private final IndexedDocumentRepository repository;

    public IndexScheduler(
            @Value("${rag.inbox.path}") String inboxPath,
            FileIndexer fileIndexer,
            IndexedDocumentRepository repository) {
        this.inboxPath = inboxPath;
        this.fileIndexer = fileIndexer;
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${rag.scheduler.interval-ms:60000}")
    public void scanAndIndex() {
        Path inbox = Path.of(inboxPath);
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
            return;
        }

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
                    fileIndexer.indexFile(filePath);
                }
            } catch (Exception e) {
                log.error("Failed to process {}: {}", filePath, e.getMessage());
            }
        }
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
