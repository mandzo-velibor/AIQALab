package com.qalab.qalabai.ai.gateway;

/**
 * Rate limiter contract.
 *
 * <p>Two granularities, because they solve different problems:</p>
 * <ul>
 *   <li>{@link #allow(AiProviderType)} — protects a provider from being overrun, so a
 *       burst degrades into {@code AI_RATE_LIMITED} (HTTP 429) instead of a provider
 *       outage or a runaway bill.</li>
 *   <li>{@link #allow(AiProviderType, Long)} — additionally bounds one account, so a
 *       single tenant cannot consume the whole provider allowance. Requires the
 *       account id, which only exists now that requests are authenticated (B-013).</li>
 * </ul>
 *
 * <p>Implementations must be thread-safe.</p>
 */
public interface RateLimiter {

    /**
     * @return true when the call is allowed; false to reject with AI_RATE_LIMITED.
     */
    boolean allow(AiProviderType provider);

    /**
     * Account-scoped check. The default delegates to the provider-scoped one, so an
     * implementation that only guards providers stays correct.
     */
    default boolean allow(AiProviderType provider, Long accountId) {
        return allow(provider);
    }

    /**
     * Tokens currently available, for metrics and diagnostics. Returns -1 when the
     * limiter is disabled or has no opinion.
     */
    default int available(AiProviderType provider, Long accountId) {
        return -1;
    }
}
