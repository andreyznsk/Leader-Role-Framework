package ru.andreyz.memoryservice.search.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import ru.andreyz.memoryservice.domain.Task;
import ru.andreyz.memoryservice.search.SearchResultItem;
import ru.andreyz.memoryservice.service.TaskDescriptionService;
import ru.andreyz.memoryservice.service.TaskService;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TaskSearchProviderTest {

    @Autowired
    private TaskSearchProvider taskSearchProvider;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskDescriptionService taskDescriptionService;

    @Test
    void findsTaskByDescriptionOnly() {
        Task task = taskService.createConfirmed(LocalDate.now(), "Release train", "HIGH", null, "MANUAL", null);
        taskDescriptionService.update(task.id(), "blocked on согласование qa before release");

        List<SearchResultItem> results = taskSearchProvider.search("согласование qa", 10);

        assertThat(results).extracting(SearchResultItem::entityId).contains(String.valueOf(task.id()));
        assertThat(results).anyMatch(item -> item.entityId().equals(String.valueOf(task.id()))
                && item.snippet().contains("согласование qa"));
    }

    @Test
    void ranksActiveTaskAboveDoneTask() {
        Task todo = taskService.createConfirmed(LocalDate.now(), "Deploy release", "HIGH", null, "MANUAL", null);
        taskDescriptionService.update(todo.id(), "release qa blocker");

        Task done = taskService.createConfirmed(LocalDate.now(), "Deploy release archive", "HIGH", null, "MANUAL", null);
        taskDescriptionService.update(done.id(), "release qa blocker");
        taskService.markDone(done.id());

        List<SearchResultItem> results = taskSearchProvider.search("qa blocker", 10);

        int todoIndex = indexOf(results, String.valueOf(todo.id()));
        int doneIndex = indexOf(results, String.valueOf(done.id()));
        assertThat(todoIndex).isGreaterThanOrEqualTo(0);
        assertThat(doneIndex).isGreaterThan(todoIndex);
    }

    @Test
    void doesNotReturnDeletedTasks() {
        Task deleted = taskService.createConfirmed(LocalDate.now(), "Old release", "NORMAL", null, "MANUAL", null);
        taskDescriptionService.update(deleted.id(), "obsolete blocker");
        taskService.deleteTask(deleted.id());

        List<SearchResultItem> results = taskSearchProvider.search("obsolete blocker", 10);

        assertThat(results).extracting(SearchResultItem::entityId).doesNotContain(String.valueOf(deleted.id()));
    }

    private static int indexOf(List<SearchResultItem> results, String taskId) {
        for (int i = 0; i < results.size(); i++) {
            if (taskId.equals(results.get(i).entityId())) {
                return i;
            }
        }
        return -1;
    }
}
