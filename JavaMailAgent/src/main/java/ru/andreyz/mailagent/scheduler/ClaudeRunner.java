package ru.andreyz.mailagent.scheduler;

import ru.andreyz.mailagent.model.AgentResponse;

import java.io.IOException;

public interface ClaudeRunner {
    AgentResponse run(String prompt) throws IOException, InterruptedException;
}
