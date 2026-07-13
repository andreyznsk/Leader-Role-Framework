package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.TaskExternalIssue;

import java.util.Optional;

public interface TaskExternalIssueRepository extends CrudRepository<TaskExternalIssue, Long> {

    Optional<TaskExternalIssue> findByTaskIdAndExternalSystem(Long taskId, String externalSystem);
}
