package ru.andreyz.mailagent.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table(schema = "mailagent", value = "prompt_templates")
public record MailPromptTemplate(
    @Id Long id,
    String code,
    String template,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
