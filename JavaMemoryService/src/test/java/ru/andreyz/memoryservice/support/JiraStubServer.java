package ru.andreyz.memoryservice.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class JiraStubServer {

    private static HttpServer server;
    private static final AtomicInteger sequence = new AtomicInteger(123);
    private static final AtomicReference<String> createIssueErrorBody = new AtomicReference<>(null);
    private static final AtomicReference<Integer> createIssueStatus = new AtomicReference<>(201);

    private JiraStubServer() {
    }

    public static synchronized void ensureStarted() throws IOException {
        if (server != null) {
            return;
        }
        server = HttpServer.create(new InetSocketAddress(19997), 0);
        server.createContext("/rest/api/2/myself", exchange -> json(exchange, 200, """
                {"accountId":"leader-account","displayName":"Leader User","emailAddress":"leader@example.com"}
                """));
        server.createContext("/rest/api/2/project/ENG", exchange -> json(exchange, 200, """
                {
                  "id":"10001",
                  "key":"ENG",
                  "name":"Engineering",
                  "issueTypes":[
                    {"id":"3","name":"Task","subtask":false},
                    {"id":"4","name":"Bug","subtask":false}
                  ]
                }
                """));
        server.createContext("/rest/api/2/project/OPS", exchange -> json(exchange, 200, """
                {
                  "id":"10002",
                  "key":"OPS",
                  "name":"Operations",
                  "issueTypes":[
                    {"id":"3","name":"Task","subtask":false}
                  ]
                }
                """));
        server.createContext("/rest/api/2/user/assignable/search", JiraStubServer::handleAssignableSearch);
        server.createContext("/rest/api/2/issue", JiraStubServer::handleCreateIssue);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    public static void reset() {
        sequence.set(123);
        createIssueStatus.set(201);
        createIssueErrorBody.set(null);
    }

    public static void failCreate(int status, String body) {
        createIssueStatus.set(status);
        createIssueErrorBody.set(body);
    }

    private static void handleAssignableSearch(HttpExchange exchange) throws IOException {
        String query = queryParam(exchange.getRequestURI(), "query");
        String body = """
                [
                  {"accountId":"leader-account","displayName":"Leader User","emailAddress":"leader@example.com","active":true},
                  {"accountId":"dev-ivan","displayName":"Ivan Petrov","emailAddress":"ivan.petrov@example.com","active":true}
                ]
                """;
        if (query != null && query.toLowerCase().contains("ops")) {
            body = """
                    [
                      {"accountId":"leader-account","displayName":"Leader User","emailAddress":"leader@example.com","active":true}
                    ]
                    """;
        }
        json(exchange, 200, body);
    }

    private static void handleCreateIssue(HttpExchange exchange) throws IOException {
        if (createIssueStatus.get() != 201) {
            json(exchange, createIssueStatus.get(), createIssueErrorBody.get());
            return;
        }
        int next = sequence.incrementAndGet();
        json(exchange, 201, """
                {"id":"%d","key":"ENG-%d","self":"http://localhost:19997/rest/api/2/issue/%d"}
                """.formatted(next, next, next));
    }

    private static String queryParam(URI uri, String name) {
        if (uri == null || uri.getRawQuery() == null) {
            return null;
        }
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
