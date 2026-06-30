package ru.andreyz.memoryservice.agentworkspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import ru.andreyz.common.agent.AgentClient;
import ru.andreyz.common.agent.AgentException;

import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final AgentClient agentClient;
    private final JdbcTemplate jdbc;

    @Value("${agent.provider:mock}")
    private String configuredProvider;

    public AgentChatController(AgentClient agentClient, JdbcTemplate jdbc) {
        this.agentClient = agentClient;
        this.jdbc = jdbc;
    }

    @PostMapping("/chat/run")
    public ResponseEntity<AgentChatRunResponse> run(@RequestBody AgentChatRunRequest req) {
        if (req.prompt() == null || req.prompt().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        long start = System.currentTimeMillis();
        String provider = configuredProvider;

        try {
            String result = agentClient.complete(req.prompt());
            long duration = System.currentTimeMillis() - start;
            audit("CHAT", provider, req.prompt(), "SUCCESS", duration, null);
            return ResponseEntity.ok(AgentChatRunResponse.success(provider, duration, result));
        } catch (AgentException e) {
            long duration = System.currentTimeMillis() - start;
            log.warn("Agent chat run failed: {}", e.getMessage());
            log.error("", e);
            audit("CHAT", provider, req.prompt(), "ERROR", duration, e.getMessage());
            return ResponseEntity.ok(AgentChatRunResponse.error(provider, duration, e.getMessage()));
        }
    }

    private void audit(String mode, String provider, String prompt, String status, long durationMs, String error) {
        try {
            jdbc.update("""
                INSERT INTO memory.agent_workspace_runs(id, mode, provider, prompt, status, duration_ms, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), mode, provider, prompt, status, durationMs, error);
        } catch (Exception ex) {
            log.warn("Failed to audit agent run: {}", ex.getMessage());
            log.error("", ex);
        }
    }
}
