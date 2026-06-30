package ru.andreyz.memoryservice.agentworkspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.List;

@Configuration
@EnableWebSocket
public class AgentConsoleWebSocketConfig implements WebSocketConfigurer {

    private final AgentConsoleWebSocketHandler handler;

    public AgentConsoleWebSocketConfig(AgentConsoleWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/agent-console").setAllowedOrigins("*");
    }
}
