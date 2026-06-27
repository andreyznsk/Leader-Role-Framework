package ru.andreyz.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.common.agent.CodeProcessAgentClient;
import ru.andreyz.common.agent.GigaChatAgentClient;
import ru.andreyz.common.agent.MockAgentClient;
import ru.andreyz.common.agent.OllamaAgentClient;

@AutoConfiguration
public class AgentClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentClientConfig.class);

    @Bean
    @ConditionalOnMissingBean(AgentClient.class)
    @ConditionalOnProperty(name = "agent.provider", havingValue = "agent", matchIfMissing = true)
    public AgentClient agentAgentClient(@Value("${agent.command:}") String agentCommand,
                                        @Value("${agent.timeout-minutes:5}") int timeoutMinutes,
                                        Environment environment) {
        boolean providerExplicitlyConfigured = environment.containsProperty("agent.provider");
        if (!StringUtils.hasText(agentCommand)) {
            if (providerExplicitlyConfigured) {
                throw new IllegalStateException("agent.command must be set when agent.provider=agent");
            }
            agentCommand = "agent --print";
        }
        log.info("AgentClient provider: {}", agentCommand);
        return new CodeProcessAgentClient(agentCommand, timeoutMinutes);
    }

    @Bean
    @ConditionalOnMissingBean(AgentClient.class)
    @ConditionalOnProperty(name = "agent.provider", havingValue = "mock")
    public AgentClient mockAgentClient(
            @Value("${agent.mock.response:}") String fixedResponse) {
        log.warn("AgentClient provider: MOCK");
        return new MockAgentClient(fixedResponse);
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.ai.ollama.api.OllamaChatOptions")
    @ConditionalOnProperty(name = "agent.provider", havingValue = "ollama")
    public ChatClient ollamaChatClient(ChatClient.Builder builder) {
        return builder
                .defaultAdvisors(SimpleLoggerAdvisor.builder().order(4).build())
                .defaultOptions(OllamaChatOptions.builder()
                        .temperature(0.0)
                        .topP(1.0)
                        .topK(20)
                        .build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(AgentClient.class)
    @ConditionalOnProperty(name = "agent.provider", havingValue = "ollama")
    public AgentClient ollamaAgentClient(ChatClient chatClient) {
        log.info("AgentClient provider: ollama");
        return new OllamaAgentClient(chatClient);
    }

    @Bean
    @ConditionalOnProperty(name = "agent.provider", havingValue = "gigachat")
    public ChatClient gigaChatClient(ChatClient.Builder builder) {
        return builder
                .defaultAdvisors(SimpleLoggerAdvisor.builder().order(4).build())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(AgentClient.class)
    @ConditionalOnProperty(name = "agent.provider", havingValue = "gigachat")
    public AgentClient gigaChatAgentClient(ChatClient chatClient) {
        log.info("AgentClient provider: gigachat");
        return new GigaChatAgentClient(chatClient);
    }
}
