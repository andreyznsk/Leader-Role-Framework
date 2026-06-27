package ru.andreyz.mailagent.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.mailagent.model.ProcessedEmail;

import java.util.List;
import java.util.Optional;

public interface ProcessedEmailRepository extends CrudRepository<ProcessedEmail, Long> {
    Optional<ProcessedEmail> findByEmailId(String emailId);
    List<ProcessedEmail> findByStatusOrderByLastAttemptAtAscCreatedAtAsc(String status);
}
