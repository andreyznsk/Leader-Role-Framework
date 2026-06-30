package ru.andreyz.common.agent;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GigaChatAgentClient implements AgentClient {


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
