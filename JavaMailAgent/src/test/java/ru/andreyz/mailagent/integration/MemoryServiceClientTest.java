package ru.andreyz.mailagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.PendingTaskRequest;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MemoryServiceClientTest {

    @Test
    void createPendingTaskSkippedWhenDisabled() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(false);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        // Не должен выбрасывать даже при недоступном URL
        assertDoesNotThrow(() -> client.createPendingTask(
            new PendingTaskRequest("Test", "description", "email-001", "user@test.com", "NORMAL")
        ));
    }

    @Test
    void createCaptureSkippedWhenDisabled() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(false);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        assertDoesNotThrow(() -> client.createCapture("FYI text", "email", "email-001"));
    }

    @Test
    void isHealthyReturnsFalseWhenDisabled() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(false);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        assertFalse(client.isHealthy());
    }

    @Test
    void isHealthyReturnsFalseWhenUnreachable() {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(true);

        MemoryServiceClient client = new MemoryServiceClient(new ObjectMapper(), props);

        assertFalse(client.isHealthy());
    }

    @Test
    void createPendingTaskRetriesWithBackoffAndUsesThirtySecondTimeout() throws Exception {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(true);

        HttpClient httpClient = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> successResponse = mock(HttpResponse.class);
        when(successResponse.statusCode()).thenReturn(201);

        List<Duration> delays = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new java.io.IOException("first"))
            .thenThrow(new java.io.IOException("second"))
            .thenReturn(successResponse);

        MemoryServiceClient client = new MemoryServiceClient(
            new ObjectMapper(), props, httpClient, delays::add
        );

        assertDoesNotThrow(() -> client.createPendingTask(
            new PendingTaskRequest("Test", "description", "email-001", "user@test.com", "NORMAL")
        ));

        verify(httpClient, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5)), delays);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, atLeastOnce()).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        assertEquals(Duration.ofSeconds(30), requestCaptor.getValue().timeout().orElseThrow());
    }

    @Test
    void createCaptureFailsAfterAllRetries() throws Exception {
        MailConfig.MemoryServiceProperties props = new MailConfig.MemoryServiceProperties();
        props.setUrl("http://localhost:19999");
        props.setEnabled(true);

        HttpClient httpClient = mock(HttpClient.class);
        List<Duration> delays = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new java.io.IOException("attempt-1"))
            .thenThrow(new java.io.IOException("attempt-2"))
            .thenThrow(new java.io.IOException("attempt-3"))
            .thenThrow(new java.io.IOException("attempt-4"));

        MemoryServiceClient client = new MemoryServiceClient(
            new ObjectMapper(), props, httpClient, delays::add
        );

        IllegalStateException exception = assertThrows(IllegalStateException.class,
            () -> client.createCapture("FYI text", "email", "email-001"));

        verify(httpClient, times(4)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10)), delays);
        assertEquals("Failed to save capture to memory-service", exception.getMessage());
    }
}
