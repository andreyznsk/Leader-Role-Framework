package ru.andreyz.mailagent.model;

public enum MailProcessingRoute {
    NONE,
    PLAN_APPEND,
    MEMORY_PENDING_TASK,
    MEMORY_CAPTURE,
    NOTICE_WRITE,
    MOVE_TO_PROCESSED,
    MARK_AS_READ
}
