package ru.andreyz.mailagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.mailagent.integration.MemorySearchRequest;
import ru.andreyz.mailagent.integration.MemorySearchResponse;
import ru.andreyz.mailagent.integration.MemorySearchResultItem;
import ru.andreyz.mailagent.integration.MemoryServiceClient;
import ru.andreyz.mailagent.model.AgentResponse;
import ru.andreyz.mailagent.model.AgentResponseType;
import ru.andreyz.mailagent.model.Email;
import ru.andreyz.mailagent.scheduler.MailLinkingPromptBuilder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MailLinkingServiceTest {

    private MemoryServiceClient memoryServiceClient;
    private MailLinkingPromptBuilder promptBuilder;
    private AgentClient agentClient;
    private MailLinkingService service;

    @BeforeEach
    void setUp() {
        memoryServiceClient = mock(MemoryServiceClient.class);
        promptBuilder = mock(MailLinkingPromptBuilder.class);
        agentClient = mock(AgentClient.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new MailLinkingService(memoryServiceClient, promptBuilder, agentClient, objectMapper);
    }

    @Test
    void apply_mergesUpdateTaskDecisionIntoRequestClassification() {
        Email email = new Email("mail-1", "RE: Release", "sender@test.com",
                List.of("dev1@test.com", "dev2@test.com"),
                "Need to move deadline to Friday",
                "<mail-1@test.com>",
                "conv-release-1",
                "<parent@test.com>",
                LocalDateTime.of(2026, 6, 30, 10, 0), "Inbox");
        AgentResponse classification = new AgentResponse(
                AgentResponseType.REQUEST,
                "mail-1",
                "Initial summary",
                "- [ ] [HIGH] Release follow-up — от sender@test.com",
                "Release follow-up",
                "HIGH",
                "sender@test.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        MemorySearchResponse searchResponse = new MemorySearchResponse(
                "release sender@test.com",
                "QUICK",
                List.of("TASK", "NOTICE"),
                null,
                List.of(new MemorySearchResultItem(
                        "TASK",
                        "Release tracking",
                        "Existing release task",
                        "/ui/tasks/42/edit",
                        "42",
                        "TASK",
                        0.91,
                        Instant.parse("2026-06-30T08:00:00Z"),
                        List.of("title", "description")
                ))
        );

        when(memoryServiceClient.search(any(MemorySearchRequest.class))).thenReturn(searchResponse);
        when(promptBuilder.build(email, classification, searchResponse)).thenReturn("prompt");
        when(agentClient.complete("prompt")).thenReturn("""
                {
                  "decision": "UPDATE_TASK",
                  "confidence": 0.94,
                  "targetTaskId": 42,
                  "title": "Release tracking",
                  "summary": "Deadline moved",
                  "reason": "Same release thread",
                  "proposedDescriptionAppend": "New deadline: Friday",
                  "matchedSources": ["TASK-42"]
                }
                """);

        AgentResponse result = service.apply(email, classification);

        assertThat(result.type()).isEqualTo(AgentResponseType.REQUEST);
        assertThat(result.pendingType()).isEqualTo("UPDATE_TASK");
        assertThat(result.suggestedTaskId()).isEqualTo(42L);
        assertThat(result.agentConfidence()).isEqualTo(0.94);
        assertThat(result.agentReason()).isEqualTo("Same release thread");
        assertThat(result.proposedDescriptionAppend()).isEqualTo("New deadline: Friday");
        assertThat(result.taskTitle()).isEqualTo("Release tracking");
        assertThat(result.note()).isEqualTo("Deadline moved");
        verify(memoryServiceClient).search(argThat(request ->
                request.query().contains("dev1@test.com")
                        && request.query().contains("dev2@test.com")
                        && request.query().contains("conv-release-1")
        ));
        verify(promptBuilder).build(email, classification, searchResponse);
        verify(agentClient).complete("prompt");
    }

    @Test
    void apply_returnsNoiseWhenDecisionIsIgnore() {
        Email email = new Email("mail-2", "RE: FYI", "sender@test.com", "No action required",
                LocalDateTime.of(2026, 6, 30, 11, 0), "Inbox");
        AgentResponse classification = new AgentResponse(
                AgentResponseType.REQUEST,
                "mail-2",
                "Initial request interpretation",
                "- [ ] [NORMAL] FYI — от sender@test.com",
                "FYI",
                "NORMAL",
                "sender@test.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        MemorySearchResponse searchResponse = new MemorySearchResponse("fyi", "QUICK", List.of("TASK"), null, List.of());

        when(memoryServiceClient.search(any(MemorySearchRequest.class))).thenReturn(searchResponse);
        when(promptBuilder.build(email, classification, searchResponse)).thenReturn("prompt");
        when(agentClient.complete("prompt")).thenReturn("""
                {
                  "decision": "IGNORE",
                  "confidence": 0.77,
                  "targetTaskId": null,
                  "title": null,
                  "summary": null,
                  "reason": "Informational thread only",
                  "proposedDescriptionAppend": null,
                  "matchedSources": []
                }
                """);

        AgentResponse result = service.apply(email, classification);

        assertThat(result.type()).isEqualTo(AgentResponseType.NOISE);
        assertThat(result.agentReason()).isEqualTo("Informational thread only");
        assertThat(result.agentConfidence()).isEqualTo(0.77);
        assertThat(result.pendingType()).isNull();
    }

    @Test
    void apply_fallsBackToOriginalClassificationWhenLinkingFails() {
        Email email = new Email("mail-3", "RE: Release", "sender@test.com", "Need update",
                LocalDateTime.of(2026, 6, 30, 12, 0), "Inbox");
        AgentResponse classification = new AgentResponse(
                AgentResponseType.REQUEST,
                "mail-3",
                "Initial summary",
                "- [ ] [HIGH] Release update — от sender@test.com",
                "Release update",
                "HIGH",
                "sender@test.com",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        when(memoryServiceClient.search(any(MemorySearchRequest.class)))
                .thenThrow(new IllegalStateException("search failed"));

        AgentResponse result = service.apply(email, classification);

        assertThat(result).isEqualTo(classification);
        verify(memoryServiceClient).search(any(MemorySearchRequest.class));
        verifyNoInteractions(promptBuilder, agentClient);
    }

    @Test
    void apply_skipsLinkingForNonRequestMessages() {
        Email email = new Email("mail-4", "Noise", "sender@test.com", "Build finished",
                LocalDateTime.of(2026, 6, 30, 13, 0), "Inbox");
        AgentResponse classification = new AgentResponse(
                AgentResponseType.NOISE,
                "mail-4",
                "Noise",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        AgentResponse result = service.apply(email, classification);

        assertThat(result).isEqualTo(classification);
        verifyNoInteractions(memoryServiceClient, promptBuilder, agentClient);
    }
}
