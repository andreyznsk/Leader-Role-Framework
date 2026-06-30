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
        return loadPrompt(MailPromptTemplates.CLASSIFICATION_PROMPT_CODE, MailPromptTemplates.DEFAULT_CLASSIFICATION_PROMPT);
    }

    public String saveClassificationPrompt(String template) {
        return savePrompt(MailPromptTemplates.CLASSIFICATION_PROMPT_CODE, template, MailPromptTemplates.DEFAULT_CLASSIFICATION_PROMPT);
    }

    public String loadLinkingPrompt() {
        return loadPrompt(MailPromptTemplates.LINKING_PROMPT_CODE, MailPromptTemplates.DEFAULT_LINKING_PROMPT);
    }

    public String saveLinkingPrompt(String template) {
        return savePrompt(MailPromptTemplates.LINKING_PROMPT_CODE, template, MailPromptTemplates.DEFAULT_LINKING_PROMPT);
    }

    private String loadPrompt(String code, String defaultValue) {
        return repository.findByCode(code)
            .map(MailPromptTemplate::template)
            .filter(template -> template != null && !template.isBlank())
            .orElse(defaultValue);
    }

    private String savePrompt(String code, String template, String defaultValue) {
        String normalized = normalizeTemplate(template, defaultValue);
        LocalDateTime now = LocalDateTime.now();
        MailPromptTemplate current = repository.findByCode(code)
            .orElse(null);
        MailPromptTemplate updated = new MailPromptTemplate(
            current != null ? current.id() : null,
            code,
            normalized,
            current != null ? current.createdAt() : now,
            now
        );
        repository.save(updated);
        return normalized;
    }

    private String normalizeTemplate(String template, String defaultValue) {
        if (template == null) {
            return defaultValue;
        }
        String normalized = template.replace("\r\n", "\n");
        return normalized.isBlank() ? defaultValue : normalized;
    }
}
