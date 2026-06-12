package ru.andreyz.common.agent;

/**
 * Common contract for LLM calls in LeaderOS.
 */
public interface AgentClient {

    /**
     * Sends a ready prompt to an LLM and returns a raw text response.
     *
     * @param prompt prompt built by a service-specific prompt builder
     * @return raw model response
     * @throws AgentException when provider call fails or times out
     */
    String complete(String prompt) throws AgentException;
}
