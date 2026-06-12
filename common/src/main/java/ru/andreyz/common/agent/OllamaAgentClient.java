package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public class OllamaAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaAgentClient.class);

    private final ChatClient chatClient;

    public OllamaAgentClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    public void init() {
        log.info("AgentClient: Ollama (Spring AI ChatClient)");
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            return chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            throw new AgentException("Ollama call failed: " + e.getMessage(), e);
        }
    }
}
