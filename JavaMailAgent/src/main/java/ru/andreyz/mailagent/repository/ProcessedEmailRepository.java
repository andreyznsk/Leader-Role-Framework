package ru.andreyz.mailagent.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.mailagent.model.ProcessedEmail;

public interface ProcessedEmailRepository extends CrudRepository<ProcessedEmail, Long> {
    boolean existsByEmailId(String emailId);
}
