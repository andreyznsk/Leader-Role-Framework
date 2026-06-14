package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Component;
import ru.andreyz.memoryservice.domain.UsageEventType;

@Component
public class UsageSavedTimePolicy {

    public int defaultSavedMinutes(UsageEventType eventType) {
        if (eventType == null) {
            return 0;
        }
        return switch (eventType) {
            case ASK_ANSWERED -> 15;
            case RAG_RESULT_USED -> 10;
            case MAIL_TASK_CREATED -> 3;
            case CAPTURE_PROCESSED -> 2;
            case TASK_CREATED -> 1;
            default -> 0;
        };
    }
}
