package ru.andreyz.memoryservice.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider memoryTools(
            ContextTools contextTools,
            TaskTools taskTools,
            IncidentTools incidentTools,
            RiskTools riskTools,
            PeopleTools peopleTools,
            KnowledgeTools knowledgeTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(contextTools, taskTools, incidentTools, riskTools, peopleTools, knowledgeTools)
                .build();
    }
}
