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

    @Autowired
    private TaskTimelineService taskTimelineService;

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
    void updateTaskDate_movesTaskAndKeepsStatus() {
        LocalDate initialDate = LocalDate.now().minusDays(2);
        LocalDate targetDate = LocalDate.now().plusDays(1);
        Task task = taskService.createConfirmed(initialDate, "Move with status", "HIGH", null, "MANUAL", null);
        taskService.updateStatus(task.id(), "IN_PROGRESS");

        Task updated = taskService.updateTaskDate(task.id(), targetDate);

        assertThat(updated.status()).isEqualTo("IN_PROGRESS");
        assertThat(updated.dueDate()).isEqualTo(targetDate);
        assertThat(taskService.findByDate(targetDate)).anyMatch(it -> it.id().equals(task.id()));
    }

    @Test
    void moveOverdueToToday_movesOnlyActiveOverdueTasks() {
        LocalDate today = LocalDate.now();
        Task overdueTodo = taskService.createConfirmed(today.minusDays(3), "Overdue TODO", "NORMAL", null, "MANUAL", null);
        Task overdueDone = taskService.createConfirmed(today.minusDays(4), "Overdue DONE", "NORMAL", null, "MANUAL", null);
        taskService.markDone(overdueDone.id());
        Task futureTodo = taskService.createConfirmed(today.plusDays(2), "Future TODO", "NORMAL", null, "MANUAL", null);

        int moved = taskService.moveOverdueToToday();

        assertThat(moved).isGreaterThanOrEqualTo(1);
        assertThat(taskService.findById(overdueTodo.id()).dueDate()).isEqualTo(today);
        assertThat(taskService.findById(overdueDone.id()).dueDate()).isEqualTo(today.minusDays(4));
        assertThat(taskService.findById(futureTodo.id()).dueDate()).isEqualTo(today.plusDays(2));
        assertThat(taskService.findByDate(today)).anyMatch(it -> it.id().equals(overdueTodo.id()));
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

    @Test
    void linkPendingToTask_archivesPendingAndAddsEmailLinkedTimelineEvent() {
        Task target = taskService.createConfirmed(LocalDate.now(), "Existing task", "HIGH", null, "MANUAL", null);
        Task pending = taskService.createPending(
                "Mail follow-up",
                "Pending description",
                "msg-link-test",
                "sender@test.com",
                "NORMAL",
                LocalDate.now().plusDays(1),
                "mail-agent",
                "LINK_TO_TASK",
                target.id(),
                0.93,
                "Same release thread",
                "EMAIL",
                "RE: Release",
                "sender@test.com",
                null
        );

        Task linked = taskService.linkPendingToTask(pending.id(), null, false);
        Task archivedPending = taskService.findById(pending.id());

        assertThat(linked.id()).isEqualTo(target.id());
        assertThat(archivedPending.status()).isEqualTo("ARCHIVED");
        assertThat(archivedPending.linkedToTaskId()).isEqualTo(target.id());
        assertThat(archivedPending.linkedAt()).isNotNull();
        assertThat(taskTimelineService.findEvents(target.id()))
                .extracting(event -> event.eventType())
                .contains("EMAIL_LINKED");
    }

    @Test
    void linkPendingToTask_canUseAlternativeTargetAndAppendDescription() {
        Task suggested = taskService.createConfirmed(LocalDate.now(), "Suggested task", "NORMAL", null, "MANUAL", null);
        Task alternative = taskService.createConfirmed(LocalDate.now(), "Alternative task", "HIGH", "Initial description", "MANUAL", null);
        Task pending = taskService.createPending(
                "Mail update",
                "Pending update description",
                "msg-update-test",
                "sender@test.com",
                "HIGH",
                LocalDate.now().plusDays(2),
                "mail-agent",
                "UPDATE_TASK",
                suggested.id(),
                0.88,
                "Deadline moved in reply",
                "EMAIL",
                "RE: Deadline",
                "sender@test.com",
                "New deadline: Friday"
        );

        Task linked = taskService.linkPendingToTask(pending.id(), alternative.id(), true);
        Task archivedPending = taskService.findById(pending.id());

        assertThat(linked.id()).isEqualTo(alternative.id());
        assertThat(taskDescriptionService.getContent(alternative.id()))
                .contains("Initial description")
                .contains("New deadline: Friday");
        assertThat(archivedPending.status()).isEqualTo("ARCHIVED");
        assertThat(archivedPending.linkedToTaskId()).isEqualTo(alternative.id());
        assertThat(archivedPending.linkedAt()).isNotNull();
        assertThat(taskTimelineService.findEvents(alternative.id()))
                .extracting(event -> event.eventType())
                .contains("EMAIL_LINKED", "DESCRIPTION_UPDATED");
    }
}
