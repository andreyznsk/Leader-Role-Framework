package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.request.HttpClientWebRequest;
import org.apache.http.ConnectionClosedException;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class WrappedHttpClientWebRequestTest {

    @Test
    void closeIgnoresPrematureChunkTermination() throws Exception {
        HttpClientWebRequest delegate = mock(HttpClientWebRequest.class);
        doThrow(new ConnectionClosedException("Premature end of chunk coded message body: closing chunk expected"))
                .when(delegate).close();

        WrappedHttpClientWebRequest request = new WrappedHttpClientWebRequest(delegate);

        assertDoesNotThrow(request::close);
    }

    @Test
    void closePropagatesOtherIoFailures() throws Exception {
        HttpClientWebRequest delegate = mock(HttpClientWebRequest.class);
        doThrow(new IOException("socket closed")).when(delegate).close();

        WrappedHttpClientWebRequest request = new WrappedHttpClientWebRequest(delegate);

        IOException thrown = assertThrows(IOException.class, request::close);
        assertTrue(thrown.getMessage().contains("socket closed"));
    }

    @Test
    void prematureChunkMatcherIsSpecific() {
        assertTrue(WrappedHttpClientWebRequest.isPrematureChunkTermination(
                new ConnectionClosedException("Premature end of chunk coded message body: closing chunk expected")));
        assertFalse(WrappedHttpClientWebRequest.isPrematureChunkTermination(
                new ConnectionClosedException("Connection closed unexpectedly")));
    }
}
