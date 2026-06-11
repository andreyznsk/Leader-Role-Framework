package ru.andreyz.memoryservice.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

@Service
public class TaskFileService {

    private final Path tasksDir;

    public TaskFileService(@Value("${app.workspace.dir}") String workspaceDir) {
        this.tasksDir = Path.of(workspaceDir).resolve("tasks");
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create tasks dir: " + tasksDir, e);
        }
    }

    public Optional<String> read(long id) {
        Path file = taskFile(id);
        if (!Files.exists(file)) return Optional.empty();
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void write(long id, String content) {
        try {
            Files.writeString(taskFile(id), content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(long id) {
        try {
            Files.deleteIfExists(taskFile(id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String filePath(long id) {
        return tasksDir.resolve(fileName(id)).toString();
    }

    private Path taskFile(long id) {
        return tasksDir.resolve(fileName(id));
    }

    private String fileName(long id) {
        return "TASK-%03d.md".formatted(id);
    }
}
