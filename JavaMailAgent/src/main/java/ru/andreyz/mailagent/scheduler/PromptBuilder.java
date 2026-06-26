package ru.andreyz.mailagent.scheduler;

import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

@Component
public class PromptBuilder {

    private final MailRuntimeConfigService runtimeConfigService;

    public PromptBuilder(MailRuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    public String build(Email email) {
        String template = runtimeConfigService.snapshot().classificationPrompt();
        return template
            .replace("{{from}}", safe(email.from()))
            .replace("{{subject}}", safe(email.subject()))
            .replace("{{body}}", safe(email.body()))
            .replace("{{emailId}}", safe(email.id()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
