package ru.andreyz.mailagent.service;

import org.springframework.stereotype.Service;
import ru.andreyz.mailagent.model.MailPromptTemplate;
import ru.andreyz.mailagent.repository.MailPromptTemplateRepository;

import java.time.LocalDateTime;

@Service
public class MailPromptTemplateService {

    private final MailPromptTemplateRepository repository;

    public MailPromptTemplateService(MailPromptTemplateRepository repository) {
        this.repository = repository;
    }

    public String loadClassificationPrompt() {
        return repository.findByCode(MailPromptTemplates.CLASSIFICATION_PROMPT_CODE)
            .map(MailPromptTemplate::template)
            .filter(template -> template != null && !template.isBlank())
            .orElse(MailPromptTemplates.DEFAULT_CLASSIFICATION_PROMPT);
    }

    public String saveClassificationPrompt(String template) {
        String normalized = normalizeTemplate(template);
        LocalDateTime now = LocalDateTime.now();
        MailPromptTemplate current = repository.findByCode(MailPromptTemplates.CLASSIFICATION_PROMPT_CODE)
            .orElse(null);
        MailPromptTemplate updated = new MailPromptTemplate(
            current != null ? current.id() : null,
            MailPromptTemplates.CLASSIFICATION_PROMPT_CODE,
            normalized,
            current != null ? current.createdAt() : now,
            now
        );
        repository.save(updated);
        return normalized;
    }

    private String normalizeTemplate(String template) {
        if (template == null) {
            return MailPromptTemplates.DEFAULT_CLASSIFICATION_PROMPT;
        }
        String normalized = template.replace("\r\n", "\n");
        return normalized.isBlank() ? MailPromptTemplates.DEFAULT_CLASSIFICATION_PROMPT : normalized;
    }
}
