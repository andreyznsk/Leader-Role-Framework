package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.integration.MemorySearchResponse;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.service.MailRuntimeConfigService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MailLinkingPromptBuilder {

    private final MailRuntimeConfigService runtimeConfigService;
    private final ObjectMapper objectMapper;

    public MailLinkingPromptBuilder(MailRuntimeConfigService runtimeConfigService,
                                    ObjectMapper objectMapper) {
        this.runtimeConfigService = runtimeConfigService;
        this.objectMapper = objectMapper;
    }

    public String build(Email email, AgentResponse classification, MemorySearchResponse searchResponse) {
        String template = runtimeConfigService.snapshot().linkingPrompt();
        return template.replace("{{context}}", toJson(buildContext(email, classification, searchResponse)));
    }

    private Map<String, Object> buildContext(Email email, AgentResponse classification, MemorySearchResponse searchResponse) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("email", email);
        context.put("normalizedSubject", normalizeSubject(email.subject()));
        context.put("thread", buildThreadContext(email));
        context.put("classification", classification);
        context.put("search", searchResponse);
        return context;
    }

    private Map<String, Object> buildThreadContext(Email email) {
        Map<String, Object> thread = new LinkedHashMap<>();
        thread.put("messageId", email.messageId());
        thread.put("conversationId", email.conversationId());
        thread.put("inReplyTo", email.inReplyTo());
        thread.put("recipients", email.recipients() != null ? email.recipients() : List.of());
        return thread;
    }

    private String normalizeSubject(String subject) {
        if (subject == null) {
            return "";
        }
        String normalized = subject.trim();
        while (normalized.matches("(?i)^(re|fw|fwd):\\s+.*")) {
            normalized = normalized.replaceFirst("(?i)^(re|fw|fwd):\\s+", "").trim();
        }
        return normalized;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize mail linking prompt context", e);
        }
    }
}
