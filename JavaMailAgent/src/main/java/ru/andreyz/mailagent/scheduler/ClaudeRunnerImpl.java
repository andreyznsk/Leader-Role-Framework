package ru.andreyz.mailagent.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.andreyz.mailagent.config.MailConfig;
import ru.andreyz.mailagent.model.AgentResponse;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "mock.agent", havingValue = "false", matchIfMissing = true)
public class ClaudeRunnerImpl implements ClaudeRunner {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRunnerImpl.class);

    private final ObjectMapper objectMapper;
    private final int timeoutMinutes;

    public ClaudeRunnerImpl(ObjectMapper objectMapper, MailConfig.AgentProperties agentProperties) {
        this.objectMapper = objectMapper;
        this.timeoutMinutes = agentProperties.getTimeoutMinutes();
    }

    @Override
    public AgentResponse run(String prompt) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("claude", "--print");
        pb.redirectErrorStream(false);

        Process process = pb.start();

        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(prompt);
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Claude timed out after " + timeoutMinutes + " minutes");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.debug("Claude raw output: {}", stdout);

        return parseResponse(stdout);
    }

    private AgentResponse parseResponse(String raw) throws IOException {
        String trimmed = raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IOException("Claude response does not contain valid JSON: " + trimmed);
        }
        String json = trimmed.substring(start, end + 1);
        return objectMapper.readValue(json, AgentResponse.class);
    }
}
