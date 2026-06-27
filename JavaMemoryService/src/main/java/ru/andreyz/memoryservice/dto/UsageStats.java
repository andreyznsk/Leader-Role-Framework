package ru.andreyz.memoryservice.dto;

import java.util.Map;

public record UsageStats(
        String period,
        long questionsAsked,
        long successfulAnswers,
        double successRate,
        long ragSearches,
        long agentCreatedTasks,
        long capturesProcessed,
        long savedMinutes,
        double savedHours,
        Map<String, Long> eventsBySource
) {}
