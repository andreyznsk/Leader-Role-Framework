package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.TaskAttachment;

import java.util.List;

public interface TaskAttachmentRepository extends CrudRepository<TaskAttachment, Long> {
    List<TaskAttachment> findByTaskIdOrderByCreatedAtDesc(Long taskId);
}
