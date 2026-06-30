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
            .replace("{{recipients}}", safe(join(email.recipients())))
            .replace("{{subject}}", safe(email.subject()))
            .replace("{{body}}", safe(email.body()))
            .replace("{{emailId}}", safe(email.id()))
            .replace("{{messageId}}", safe(email.messageId()))
            .replace("{{conversationId}}", safe(email.conversationId()))
            .replace("{{inReplyTo}}", safe(email.inReplyTo()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String join(java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
    }
}
