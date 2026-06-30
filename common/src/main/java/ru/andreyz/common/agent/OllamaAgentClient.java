package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OllamaAgentClient implements AgentClient {


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
