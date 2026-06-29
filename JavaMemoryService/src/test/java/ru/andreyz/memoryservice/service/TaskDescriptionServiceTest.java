package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.TaskDescriptionResponse;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class TaskDescriptionServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskDescriptionService taskDescriptionService;

    @Autowired
    private TaskFileService taskFileService;

    @Test
    void update_createsDescriptionAndDoesNotWriteFile() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Release", "HIGH", null, "MANUAL", null);

        TaskDescriptionResponse response = taskDescriptionService.update(task.id(), "## Context\nblocked by QA");

        assertThat(response.taskId()).isEqualTo(task.id());
        assertThat(response.contentMd()).isEqualTo("## Context\nblocked by QA");
        assertThat(response.contentHash()).hasSize(64);
        assertThat(taskFileService.read(task.id())).isEmpty();
    }

    @Test
    void update_replacesExistingDescription() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Release 2", "NORMAL", "first", "MANUAL", null);

        TaskDescriptionResponse response = taskDescriptionService.update(task.id(), "second version");

        assertThat(response.contentMd()).isEqualTo("second version");
        assertThat(taskDescriptionService.getContent(task.id())).isEqualTo("second version");
    }

    @Test
    void get_unknownTaskFails() {
        assertThatThrownBy(() -> taskDescriptionService.get(999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Task not found");
    }

    @Test
    void importFromLegacyFile_doesNotOverrideExistingDatabaseContent() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Release 3", "NORMAL", null, "MANUAL", null);
        taskDescriptionService.update(task.id(), "db source of truth");

        taskDescriptionService.importFromLegacyFile(task.id(), "legacy file backup");

        assertThat(taskDescriptionService.getContent(task.id())).isEqualTo("db source of truth");
    }
}
