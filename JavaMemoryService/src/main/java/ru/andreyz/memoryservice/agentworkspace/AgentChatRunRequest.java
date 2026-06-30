package ru.andreyz.memoryservice.agentworkspace;

public record AgentChatRunRequest(
        String prompt,
        String provider,
        boolean includeContext
) {}
