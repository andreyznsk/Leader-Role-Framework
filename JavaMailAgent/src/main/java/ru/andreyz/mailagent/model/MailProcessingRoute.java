package ru.andreyz.mailagent.model;

public enum MailProcessingRoute {
    NONE,
    INTAKE_WRITE,
    PLAN_APPEND,
    MEMORY_PENDING_TASK,
    MEMORY_CAPTURE,
    MEMORY_NOTE,
    RAG_INTAKE,
    MOVE_TO_PROCESSED,
    MARK_AS_READ
}
