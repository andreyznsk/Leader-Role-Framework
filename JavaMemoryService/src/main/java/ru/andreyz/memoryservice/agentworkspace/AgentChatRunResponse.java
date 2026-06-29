package ru.andreyz.memoryservice.agentworkspace;

public record AgentChatRunResponse(
        String status,
        String provider,
        long durationMs,
        String response,
        String error
) {
    public static AgentChatRunResponse success(String provider, long durationMs, String response) {
        return new AgentChatRunResponse("SUCCESS", provider, durationMs, response, null);
    }

    public static AgentChatRunResponse error(String provider, long durationMs, String error) {
        return new AgentChatRunResponse("ERROR", provider, durationMs, null, error);
    }
}
