package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.TaskLink;

import java.util.List;

public interface TaskLinkRepository extends CrudRepository<TaskLink, Long> {
    List<TaskLink> findByFromTaskIdOrderByCreatedAtDesc(Long fromTaskId);
    List<TaskLink> findByToTaskIdOrderByCreatedAtDesc(Long toTaskId);
    boolean existsByFromTaskIdAndToTaskIdAndLinkType(Long fromTaskId, Long toTaskId, String linkType);
}
