package ru.andreyz.common.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;
import ru.andreyz.common.jira.AtlassianJiraClient;
import ru.andreyz.common.jira.JiraClient;
import ru.andreyz.common.jira.JiraIntegrationProperties;

@AutoConfiguration
@EnableConfigurationProperties(JiraIntegrationProperties.class)
public class JiraClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public RestClient.Builder jiraRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "jira.enabled", havingValue = "true")
    public JiraClient jiraClient(RestClient.Builder restClientBuilder,
                                 JiraIntegrationProperties properties) {
        return new AtlassianJiraClient(restClientBuilder, properties);
    }
}
