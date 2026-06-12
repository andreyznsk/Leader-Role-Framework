package ru.andreyz.mailagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentResponse(
    AgentResponseType type,
    String emailId,
    String note,
    String taskLine,
    String taskTitle,
    String priority,
    String sender,
    String draftPath,
    String captureText
) {}
