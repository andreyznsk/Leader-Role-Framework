package ru.andreyz.memoryservice.agentworkspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentConsoleWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentConsoleWebSocketHandler.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Set<String> SHELL_BLACKLIST = Set.of("bash", "sh", "zsh", "fish", "cmd", "powershell", "pwsh");

    @Value("${agentWorkspace.console.allowedCommands:claude}")
    private String allowedCommandsRaw;

    private final ObjectMapper mapper;
    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    public AgentConsoleWebSocketHandler(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), new AgentSession(session));
        log.debug("Console WS connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode node = mapper.readTree(message.getPayload());
        String type = node.path("type").asText();
        AgentSession agentSession = sessions.get(session.getId());

        switch (type) {
            case "START" -> {
                String provider = node.path("provider").asText("claude");
                handleStart(agentSession, provider);
            }
            case "STDIN" -> {
                String data = node.path("data").asText("");
                if (agentSession.process != null) {
                    agentSession.process.getOutputStream().write(data.getBytes());
                    agentSession.process.getOutputStream().flush();
                }
            }
            case "STOP" -> handleStop(agentSession);
            default -> sendJson(session, "ERROR", "message", "Unknown message type: " + type);
        }
    }

    private void handleStart(AgentSession agentSession, String provider) throws Exception {
        if (SHELL_BLACKLIST.contains(provider.toLowerCase())) {
            sendJson(agentSession.wsSession, "ERROR", "message", "Shell execution not allowed: " + provider);
            return;
        }

        List<String> allowed = List.of(allowedCommandsRaw.split(",\\s*"));
        if (!allowed.contains(provider.toLowerCase())) {
            sendJson(agentSession.wsSession, "ERROR", "message",
                    "Provider not in whitelist: " + provider + ". Allowed: " + allowed);
            return;
        }

        if (agentSession.process != null && agentSession.process.isAlive()) {
            sendJson(agentSession.wsSession, "ERROR", "message", "Session already running");
            return;
        }

        String sessionId = java.util.UUID.randomUUID().toString().substring(0, 8);
        sendJson(agentSession.wsSession, "SESSION_STARTED", "sessionId", sessionId);
        sendLog(agentSession.wsSession, "session started [" + provider + "]");

        try {
            Process process = new ProcessBuilder(provider)
                    .redirectErrorStream(false)
                    .start();
            agentSession.process = process;

            streamOutput(agentSession.wsSession, process.getInputStream(), "STDOUT");
            streamOutput(agentSession.wsSession, process.getErrorStream(), "STDERR");

            Thread.ofVirtual().start(() -> {
                try {
                    int code = process.waitFor();
                    if (agentSession.wsSession.isOpen()) {
                        ObjectNode msg = mapper.createObjectNode();
                        msg.put("type", "SESSION_STOPPED");
                        msg.put("exitCode", code);
                        agentSession.wsSession.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
                    }
                } catch (Exception e) {
                    log.debug("Process wait interrupted: {}", e.getMessage());
                    log.error("", e);
                }
            });
        } catch (IOException e) {
            log.error("", e);
            sendJson(agentSession.wsSession, "ERROR", "message", "Failed to start: " + e.getMessage());
        }
    }

    private void handleStop(AgentSession agentSession) {
        if (agentSession.process != null && agentSession.process.isAlive()) {
            agentSession.process.destroyForcibly();
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AgentSession agentSession = sessions.remove(session.getId());
        if (agentSession != null && agentSession.process != null) {
            agentSession.process.destroyForcibly();
        }
    }

    private void streamOutput(WebSocketSession session, InputStream stream, String type) {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (session.isOpen()) {
                        sendJson(session, type, "data", line);
                    }
                }
            } catch (Exception e) {
                log.debug("Stream {} ended: {}", type, e.getMessage());
                log.error("", e);
            }
        });
    }

    private void sendLog(WebSocketSession session, String text) {
        sendJson(session, "STDOUT", "data", TIME_FMT.format(LocalTime.now()) + " " + text);
    }

    private void sendJson(WebSocketSession session, String type, String key, String value) {
        if (!session.isOpen()) return;
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("type", type);
            node.put(key, value);
            session.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
        } catch (Exception e) {
            log.debug("Failed to send WS message: {}", e.getMessage());
            log.error("", e);
        }
    }

    private static class AgentSession {
        final WebSocketSession wsSession;
        volatile Process process;

        AgentSession(WebSocketSession wsSession) {
            this.wsSession = wsSession;
        }
    }
}
