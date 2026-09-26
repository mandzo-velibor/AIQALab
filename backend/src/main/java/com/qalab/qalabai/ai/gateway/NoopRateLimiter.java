package com.qalab.qalabai.ai.gateway;

/**
 * Always allows. Retained for tests and for an explicitly unlimited deployment, but
 * **no longer the active bean** — {@link TokenBucketRateLimiter} is, since an
 * unauthenticated caller could otherwise drive unbounded paid traffic.
 *
 * <p>Deliberately has no {@code @Component}: two limiter beans would make injection
 * ambiguous, and picking the wrong one silently disables the protection.</p>
 */
public class NoopRateLimiter implements RateLimiter {

    @Override
    public boolean allow(AiProviderType provider) {
        return true;
    }
}
