package ru.andreyz.common.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.andreyz.common.jira.JiraClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JiraClientConfigTest {

    private HttpServer server;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JiraClientConfig.class));

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void jiraClientCreatedWhenEnabled() throws Exception {
        startServer();
        contextRunner
                .withPropertyValues(
                        "jira.enabled=true",
                        "jira.base-url=http://localhost:" + server.getAddress().getPort(),
                        "jira.token=test-token")
                .run(context -> {
                    assertThat(context).hasSingleBean(JiraClient.class);
                    JiraClient jiraClient = context.getBean(JiraClient.class);
                    assertThat(jiraClient.testConnection().success()).isTrue();
                    assertThat(jiraClient.getProjects(Set.of("ENG")))
                            .extracting("key")
                            .containsExactly("ENG");
                });
    }

    @Test
    void jiraClientNotCreatedWhenDisabled() {
        contextRunner
                .withPropertyValues("jira.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(JiraClient.class));
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rest/api/2/myself", exchange -> json(exchange, 200, """
                {"accountId":"acc-1","displayName":"Leader User","emailAddress":"leader@example.com"}
                """));
        server.createContext("/rest/api/2/project/ENG", exchange -> json(exchange, 200, """
                {"id":"10000","key":"ENG","name":"Engineering","issueTypes":[{"id":"3","name":"Task","subtask":false}]}
                """));
        server.start();
    }

    private static void json(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(payload);
        }
    }
}
