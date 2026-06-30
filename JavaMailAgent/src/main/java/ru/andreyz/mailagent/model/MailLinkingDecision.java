package ru.andreyz.mailagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MailLinkingDecision(
        MailLinkingDecisionType decision,
        Double confidence,
        Long targetTaskId,
        String title,
        String summary,
        String reason,
        String proposedDescriptionAppend,
        List<String> matchedSources
) {
}
