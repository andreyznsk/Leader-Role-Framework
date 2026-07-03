package ru.andreyz.memoryservice.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.memoryservice.domain.TaskLabel;

import java.util.List;

public interface TaskLabelRepository extends CrudRepository<TaskLabel, Long> {
    List<TaskLabel> findByArchivedFalseOrderByNameAsc();
}
