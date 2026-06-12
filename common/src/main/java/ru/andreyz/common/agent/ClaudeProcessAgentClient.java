package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ClaudeProcessAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeProcessAgentClient.class);

    private final int timeoutMinutes;

    public ClaudeProcessAgentClient(int timeoutMinutes) {
        this.timeoutMinutes = timeoutMinutes;
    }

    @PostConstruct
    public void init() {
        log.info("AgentClient: claude --print subprocess (timeout={}m)", timeoutMinutes);
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            ProcessBuilder pb = new ProcessBuilder("claude", "--print");
            pb.redirectErrorStream(false);
            Process process = pb.start();

            try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(prompt);
            }

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                throw new AgentException("claude --print timed out after " + timeoutMinutes + "m");
            }

            if (process.exitValue() != 0) {
                String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new AgentException("claude --print exited with code " + process.exitValue() + ": " + error);
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Claude raw output ({} chars)", output.length());
            return output;
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException("claude --print failed: " + e.getMessage(), e);
        }
    }
}
