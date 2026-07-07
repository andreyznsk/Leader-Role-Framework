package ru.andreyz.memoryservice.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

@Service
public class AttachmentStorageService {

    private final Path root;

    public AttachmentStorageService(@Value("${app.attachments.dir}") String attachmentsDir) {
        this.root = Path.of(attachmentsDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create attachments dir: " + root, e);
        }
    }

    public StoredFile store(long taskId, String originalFilename, InputStream content) {
        String storedName = UUID.randomUUID() + "_" + originalFilename;
        Path taskDir = resolveTaskDir(taskId);
        try {
            Files.createDirectories(taskDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path target = taskDir.resolve(storedName).normalize();
        if (!target.startsWith(taskDir)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        try (content) {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new StoredFile(root.relativize(target).toString(), size);
    }

    public Resource load(String storageRef) {
        Path target = resolveStoredPath(storageRef);
        if (!Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Attachment file not found");
        }
        return new FileSystemResource(target);
    }

    public void delete(String storageRef) {
        Path target = resolveStoredPath(storageRef);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void deleteTaskDir(long taskId) {
        Path taskDir = resolveTaskDir(taskId);
        if (!Files.exists(taskDir)) {
            return;
        }
        try (var paths = Files.walk(taskDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolveStoredPath(String storageRef) {
        Path target = root.resolve(storageRef).normalize();
        if (!target.startsWith(root)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid storage reference");
        }
        return target;
    }

    private Path resolveTaskDir(long taskId) {
        return root.resolve(String.valueOf(taskId)).normalize();
    }

    public record StoredFile(String storageRef, long size) {}
}
