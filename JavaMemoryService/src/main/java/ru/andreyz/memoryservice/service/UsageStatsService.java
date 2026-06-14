package ru.andreyz.memoryservice.service;

import org.springframework.stereotype.Service;
import ru.andreyz.memoryservice.domain.UsageEvent;
import ru.andreyz.memoryservice.dto.UsageStats;
import ru.andreyz.memoryservice.repository.UsageEventRepository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UsageStatsService {

    private final UsageEventRepository usageEventRepository;

    public UsageStatsService(UsageEventRepository usageEventRepository) {
        this.usageEventRepository = usageEventRepository;
    }

    public UsageStats getStats(UsageStatsPeriod period) {
        var from = period.startInstant();
        var events = from == null
                ? usageEventRepository.findAllByCreatedAtDesc()
                : usageEventRepository.findByCreatedAtFrom(from);

        long questionsAsked = count(events, "ASK_QUESTION");
        long successfulAnswers = count(events, "ASK_ANSWERED");
        double successRate = questionsAsked == 0 ? 0.0 : Math.round((successfulAnswers * 1000.0 / questionsAsked)) / 10.0;
        long ragSearches = count(events, "RAG_SEARCH");
        long agentCreatedTasks = events.stream()
                .filter(event -> "TASK_CREATED".equals(event.eventType()))
                .filter(event -> !"manual-ui".equalsIgnoreCase(event.source()) && !"MANUAL".equalsIgnoreCase(event.source()))
                .count();
        long capturesProcessed = count(events, "CAPTURE_PROCESSED");
        long savedMinutes = events.stream()
                .mapToLong(event -> event.savedMinutes() != null ? event.savedMinutes() : 0)
                .sum();
        double savedHours = Math.round((savedMinutes / 60.0) * 10.0) / 10.0;
        Map<String, Long> eventsBySource = events.stream()
                .collect(Collectors.groupingBy(UsageEvent::source, LinkedHashMap::new, Collectors.counting()));

        return new UsageStats(
                period.value(),
                questionsAsked,
                successfulAnswers,
                successRate,
                ragSearches,
                agentCreatedTasks,
                capturesProcessed,
                savedMinutes,
                savedHours,
                eventsBySource
        );
    }

    private long count(Iterable<UsageEvent> events, String eventType) {
        long count = 0;
        for (UsageEvent event : events) {
            if (eventType.equals(event.eventType())) {
                count++;
            }
        }
        return count;
    }
}
