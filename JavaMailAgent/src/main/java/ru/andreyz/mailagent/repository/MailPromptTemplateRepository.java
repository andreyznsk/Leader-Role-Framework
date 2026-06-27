package ru.andreyz.mailagent.repository;

import org.springframework.data.repository.CrudRepository;
import ru.andreyz.mailagent.model.MailPromptTemplate;

import java.util.Optional;

public interface MailPromptTemplateRepository extends CrudRepository<MailPromptTemplate, Long> {

    Optional<MailPromptTemplate> findByCode(String code);
}
