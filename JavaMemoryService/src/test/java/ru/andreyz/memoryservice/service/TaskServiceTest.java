package ru.andreyz.memoryservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Task;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    @Test
    void createPending_hasCorrectStatus() {
        Task task = taskService.createPending("Pending task", null, "msg-test", "sender@test.com", "HIGH");

        assertThat(task.id()).isNotNull();
        assertThat(task.status()).isEqualTo("PENDING");
        assertThat(task.source()).isEqualTo("EMAIL");
        assertThat(task.emailId()).isEqualTo("msg-test");
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

        assertThat(rejected.status()).isEqualTo("DELETED");
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
}
