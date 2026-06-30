package ru.andreyz.memoryservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TaskDescriptionFileMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskDescriptionFileMigrationRunner.class);
    private static final Pattern TASK_FILE_PATTERN = Pattern.compile("TASK-(\\d+)\\.md");

    private final TaskFileService taskFileService;
    private final TaskDescriptionService taskDescriptionService;

    public TaskDescriptionFileMigrationRunner(TaskFileService taskFileService,
                                              TaskDescriptionService taskDescriptionService) {
        this.taskFileService = taskFileService;
        this.taskDescriptionService = taskDescriptionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        taskFileService.listExistingTaskFiles().forEach(path -> {
            Matcher matcher = TASK_FILE_PATTERN.matcher(path.getFileName().toString());
            if (!matcher.matches()) {
                return;
            }
            Long taskId = Long.parseLong(matcher.group(1));
            try {
                taskDescriptionService.importFromLegacyFile(taskId, taskFileService.readFile(path));
            } catch (IllegalArgumentException e) {
                log.debug("Skipping legacy task description file for unknown task {}: {}", taskId, path);
                log.error("", e);
            } catch (RuntimeException e) {
                log.warn("Failed to import legacy task description file {}", path, e);
            }
        });
    }
}
