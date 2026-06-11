package ru.andreyz.ragservice.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpConfig {

    @Bean
    public ToolCallbackProvider ragTools(RagMcpTools ragMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ragMcpTools)
                .build();
    }
}
