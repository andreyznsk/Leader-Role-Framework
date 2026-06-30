package ru.andreyz.mailagent.client;

import microsoft.exchange.webservices.data.core.exception.http.EWSHttpException;
import microsoft.exchange.webservices.data.core.request.HttpClientWebRequest;
import microsoft.exchange.webservices.data.core.request.HttpWebRequest;
import org.apache.http.ConnectionClosedException;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Wraps {@link HttpClientWebRequest} to fix the NPE in
 * {@code ServiceRequestBase.readResponse()} when the Exchange server
 * omits the {@code Content-Type} response header.
 * <p>
 * ews-java-api 2.0 bug: {@code HttpClientWebRequest.getResponseContentType()}
 * returns {@code null} when the server does not send a Content-Type header,
 * causing {@code readResponse()} at line 369 to NPE on
 * {@code response.getResponseContentType().startsWith("text/xml")}.
 * <p>
 * This wrapper returns {@code "text/xml; charset=utf-8"} when the real
 * content type is {@code null}.
 * <p>
 * Some Exchange servers also terminate chunked responses without the final
 * closing chunk after the SOAP payload has already been read. Apache HttpClient
 * raises {@link ConnectionClosedException} from {@link #close()} while trying
 * to consume the entity for connection reuse. We suppress only that cleanup-time
 * failure so successful SOAP calls are not downgraded to hard errors.
 */
@Slf4j
public class WrappedHttpClientWebRequest extends HttpWebRequest {

    private final HttpClientWebRequest delegate;

    public WrappedHttpClientWebRequest(HttpClientWebRequest delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getResponseContentType() throws EWSHttpException {
        String real = delegate.getResponseContentType();
        if (real == null) {
            return "text/xml; charset=utf-8";
        }
        return real;
    }

    // ---------- pure delegation ----------

    @Override
    public InputStream getInputStream() throws EWSHttpException, IOException {
        return delegate.getInputStream();
    }

    @Override
    public InputStream getErrorStream() throws EWSHttpException {
        return delegate.getErrorStream();
    }

    @Override
    public OutputStream getOutputStream() throws EWSHttpException {
        return delegate.getOutputStream();
    }

    @Override
    public void close() throws IOException {
        try {
            delegate.close();
        } catch (ConnectionClosedException e) {
            if (isPrematureChunkTermination(e)) {
                log.warn("Ignoring premature chunked-response termination during EWS response cleanup: {}", e.getMessage());
                return;
            }
            throw e;
        }
    }

    @Override
    public void prepareConnection() {
        delegate.prepareConnection();
    }

    @Override
    public Map<String, String> getResponseHeaders() throws EWSHttpException {
        return delegate.getResponseHeaders();
    }

    @Override
    public String getContentEncoding() throws EWSHttpException {
        return delegate.getContentEncoding();
    }

    @Override
    public int executeRequest() throws EWSHttpException, IOException {
        return delegate.executeRequest();
    }

    @Override
    public int getResponseCode() throws EWSHttpException {
        return delegate.getResponseCode();
    }

    @Override
    public String getResponseText() throws EWSHttpException {
        return delegate.getResponseText();
    }

    @Override
    public String getResponseHeaderField(String headerName) throws EWSHttpException {
        return delegate.getResponseHeaderField(headerName);
    }

    @Override
    public Map<String, String> getRequestProperty() throws EWSHttpException {
        return delegate.getRequestProperty();
    }

    static boolean isPrematureChunkTermination(ConnectionClosedException exception) {
        String message = exception.getMessage();
        return message != null
                && message.contains("Premature end of chunk coded message body")
                && message.contains("closing chunk expected");
    }
}
