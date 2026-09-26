package com.qalab.qalabai.ai.gateway;

/**
 * Signals that a client exhausted its own fallback chain without a usable response.
 *
 * <p>Declared in the gateway package rather than next to the client that throws it, so
 * {@link AiGateway} can recognise the condition without depending on a particular
 * provider's package.</p>
 *
 * <p>It is deliberately <em>not</em> retried by the gateway. The provider cascade is
 * already the retry mechanism: by the time this is thrown, every configured provider
 * has been tried within its budget. Retrying the identical chain immediately after
 * multiplies the cost of a failing operation by the gateway's retry count, which is
 * the opposite of what a budget is for.</p>
 */
public class ProviderCascadeExhaustedException extends RuntimeException {

    private final int upstreamCalls;

    public ProviderCascadeExhaustedException(String message, int upstreamCalls) {
        super(message);
        this.upstreamCalls = upstreamCalls;
    }

    /** How many upstream calls were actually made before giving up. */
    public int upstreamCalls() {
        return upstreamCalls;
    }

    /** Retained for callers written against the earlier accessor name. */
    public int getUpstreamCalls() {
        return upstreamCalls;
    }
}
