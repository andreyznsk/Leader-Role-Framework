package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

public class GigaChatAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(GigaChatAgentClient.class);

    private final ChatClient chatClient;

    public GigaChatAgentClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    public void init() {
        log.info("AgentClient: GigaChat (Spring AI ChatClient)");
    }

    @Override
    public String complete(String prompt) throws AgentException {
        try {
            return chatClient.prompt(prompt).call().content();
        } catch (Exception e) {
            throw new AgentException("GigaChat call failed: " + e.getMessage(), e);
        }
    }
}
