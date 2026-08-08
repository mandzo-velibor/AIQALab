package com.qalab.qalabai.ai.gateway;

/**
 * Rate limiter contract. Sprint 11 ships a placeholder implementation; real
 * token-based or per-minute limiting lands in a later sprint.
 */
public interface RateLimiter {

    /**
     * @return true when the call is allowed; false to reject with AI_RATE_LIMITED.
     */
    boolean allow(AiProviderType provider);
}
