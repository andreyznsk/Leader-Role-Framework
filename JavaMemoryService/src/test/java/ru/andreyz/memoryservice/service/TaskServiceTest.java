package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.dto.EditTaskRequest;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskDescriptionService taskDescriptionService;

    @Test
    void createPending_hasCorrectStatus() {
        LocalDate dueDate = LocalDate.now().plusDays(2);
        Task task = taskService.createPending("Pending task", null, "msg-test", "sender@test.com", "HIGH", dueDate);

        assertThat(task.id()).isNotNull();
        assertThat(task.status()).isEqualTo("PENDING");
        assertThat(task.source()).isEqualTo("EMAIL");
        assertThat(task.emailId()).isEqualTo("msg-test");
        assertThat(task.dueDate()).isEqualTo(dueDate);
    }

    @Test
    void confirmPending_movesToTodo() {
        Task pending = taskService.createPending("To confirm", null, "msg-confirm", null, "NORMAL");
        assertThat(pending.status()).isEqualTo("PENDING");

        Task confirmed = taskService.confirm(pending.id());

        assertThat(confirmed.status()).isEqualTo("TODO");
        assertThat(confirmed.planId()).isNotNull();
    }

    @Test
    void rejectPending_movesToDeleted() {
        Task pending = taskService.createPending("To reject", null, "msg-reject", null, "LOW");

        Task rejected = taskService.reject(pending.id());

        assertThat(rejected.status()).isEqualTo("ARCHIVED");
    }

    @Test
    void createConfirmed_visibleByDate() {
        LocalDate date = LocalDate.now().plusDays(10);
        taskService.createConfirmed(date, "Future task", "NORMAL", null, "MANUAL", null);

        List<Task> tasks = taskService.findByDate(date);
        assertThat(tasks).anyMatch(t -> t.title().equals("Future task"));
    }

    @Test
    void markDone_changesStatus() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Task to done",
                "NORMAL", null, "MANUAL", null);

        Task done = taskService.markDone(task.id());

        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void toggleDone_reopensDoneTaskAsTodo() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Task to reopen",
                "NORMAL", null, "MANUAL", null);
        Task done = taskService.markDone(task.id());

        Task reopened = taskService.toggleDone(done.id());

        assertThat(reopened.status()).isEqualTo("TODO");
    }

    @Test
    void edit_withDescriptionUpdatesDatabaseBackwardsCompatibly() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Task with legacy edit", "NORMAL", null, "MANUAL", null);

        Task updated = taskService.edit(task.id(), new EditTaskRequest(
                "Task with legacy edit",
                "## Context\nblocked by qa approval",
                "HIGH",
                "IN_PROGRESS",
                LocalDate.now().plusDays(1)
        ));

        assertThat(updated.priority()).isEqualTo("HIGH");
        assertThat(updated.status()).isEqualTo("IN_PROGRESS");
        assertThat(taskDescriptionService.getContent(task.id())).isEqualTo("## Context\nblocked by qa approval");
        assertThat(taskService.findById(task.id()).description()).contains("blocked by qa approval");
    }

    @Test
    void archive_hidesTaskFromDateListing() {
        LocalDate date = LocalDate.now().plusDays(5);
        Task task = taskService.createConfirmed(date, "Archive me", "NORMAL", null, "MANUAL", null);

        taskService.archive(task.id());

        assertThat(taskService.findByDate(date)).noneMatch(it -> it.id().equals(task.id()));
        assertThat(taskService.findById(task.id()).status()).isEqualTo("ARCHIVED");
    }
}
