package ru.andreyz.memoryservice.search.provider;

import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.repository.TaskRepository;
import ru.andreyz.memoryservice.search.SearchLayer;
import ru.andreyz.memoryservice.search.SearchProvider;
import ru.andreyz.memoryservice.search.SearchResultItem;

import java.util.ArrayList;
import java.util.List;

@Component
public class TaskSearchProvider implements SearchProvider {

    private final TaskRepository taskRepository;

    public TaskSearchProvider(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    @Override
    public SearchLayer layer() {
        return SearchLayer.TASK;
    }

    @Override
    public List<SearchResultItem> search(String query, int limit) {
        String q = query.toLowerCase();
        var results = new ArrayList<SearchResultItem>();

        taskRepository.findCurrentTasks().forEach(task -> {
            double score = scoreText(q, task.title(), task.description());
            if (score > 0) {
                results.add(new SearchResultItem(
                        SearchLayer.TASK,
                        task.title(),
                        task.description(),
                        "/ui/today",
                        String.valueOf(task.id()),
                        "TASK",
                        score,
                        task.updatedAt()
                ));
            }
        });

        return results.stream()
                .sorted((a, b) -> Double.compare(b.score(), a.score()))
                .limit(limit)
                .toList();
    }

    static double scoreText(String query, String title, String body) {
        if (title != null && title.toLowerCase().contains(query)) return 0.85;
        if (body != null && body.toLowerCase().contains(query)) return 0.50;
        return 0.0;
    }
}
