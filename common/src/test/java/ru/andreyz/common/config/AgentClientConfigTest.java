package ru.andreyz.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.common.agent.CodeProcessAgentClient;
import ru.andreyz.common.agent.MockAgentClient;

import static org.assertj.core.api.Assertions.assertThat;
class AgentClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentClientConfig.class));

    @Test
    void usesAgentCliByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasSingleBean(AgentClient.class)
                .getBean(AgentClient.class)
                .isInstanceOf(CodeProcessAgentClient.class));
    }

    @Test
    void usesMockWhenConfigured() {
        contextRunner
                .withPropertyValues(
                        "agent.provider=mock",
                        "agent.mock.response={\"type\":\"TASK\"}")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentClient.class);
                    assertThat(context.getBean(AgentClient.class)).isInstanceOf(MockAgentClient.class);
                    assertThat(context.getBean(AgentClient.class).complete("ignored")).isEqualTo("{\"type\":\"TASK\"}");
                });
    }

    @Test
    void requiresCommandWhenAgentProviderExplicitlyConfigured() {
        contextRunner
                .withPropertyValues("agent.provider=agent")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("agent.command must be set when agent.provider=agent");
                });
    }
}
