package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.springframework.util.StringUtils;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CodeProcessAgentClient implements AgentClient {


    private final List<String> command;
    private final int timeoutMinutes;

    public CodeProcessAgentClient(String command, int timeoutMinutes) {
        String[] tokens = StringUtils.tokenizeToStringArray(command, " ");
        if (tokens == null || tokens.length == 0) {
            throw new IllegalArgumentException("agent.command must not be blank");
        }
        this.command = List.copyOf(Arrays.asList(tokens));
        this.timeoutMinutes = timeoutMinutes;
    }

    @PostConstruct
    public void init() {
        log.info("AgentClient: {} (timeout={}m)", String.join(" ", command), timeoutMinutes);
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            return run(prompt, command, String.join(" ", command));
        } catch (AgentException e) {
            throw e;
        } catch (Exception e) {
            throw new AgentException(String.join(" ", command) + " failed: " + e.getMessage(), e);
        }
    }

    private String run(String prompt, List<String> command, String commandLabel) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        try (var writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(prompt);
        }

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new AgentException(commandLabel + " timed out after " + timeoutMinutes + "m");
        }

        if (process.exitValue() != 0) {
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new AgentException(commandLabel + " exited with code " + process.exitValue() + ": " + error);
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        log.debug("Agent raw output ({} chars)", output.length());
        return output;
    }
}
