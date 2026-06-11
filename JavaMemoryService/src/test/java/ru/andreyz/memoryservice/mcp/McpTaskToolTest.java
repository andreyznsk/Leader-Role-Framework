package ru.andreyz.memoryservice.mcp;

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
class McpTaskToolTest {

    @Autowired
    private TaskTools taskTools;

    @Test
    void createTask_thenVisibleInGetTasks() {
        String today = LocalDate.now().toString();

        Task created = taskTools.createTask("Провести 1-1", today, "HIGH", null, "AGENT");
        assertThat(created.id()).isNotNull();
        assertThat(created.title()).isEqualTo("Провести 1-1");

        List<Task> tasks = taskTools.getTasks(today, null);
        assertThat(tasks).anyMatch(t -> t.title().equals("Провести 1-1"));
    }

    @Test
    void markTaskDone_changesStatus() {
        String today = LocalDate.now().toString();

        Task task = taskTools.createTask("Задача для завершения", today, null, null, "AGENT");
        Task done = taskTools.markTaskDone(task.id());

        assertThat(done.status()).isEqualTo("DONE");
    }

    @Test
    void moveTask_appearsOnTargetDate() {
        String today = LocalDate.now().toString();
        String tomorrow = LocalDate.now().plusDays(1).toString();

        Task task = taskTools.createTask("Задача для переноса", today, null, null, "AGENT");
        Task moved = taskTools.moveTask(task.id(), tomorrow);

        List<Task> tomorrowTasks = taskTools.getTasks(tomorrow, null);
        assertThat(tomorrowTasks).anyMatch(t -> t.title().equals("Задача для переноса"));
    }
}
