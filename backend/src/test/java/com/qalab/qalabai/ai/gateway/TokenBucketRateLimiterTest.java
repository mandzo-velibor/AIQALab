package com.qalab.qalabai.ai.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rate limiter was a no-op whose own javadoc called it a placeholder. Combined
 * with the absence of authentication, that meant anyone who could reach the port could
 * drive unbounded paid LLM traffic.
 */
class TokenBucketRateLimiterTest {

    /** Account bucket deliberately wider, so the PROVIDER bucket is what binds. */
    private TokenBucketRateLimiter providerBinds;

    /** Provider bucket deliberately wider, so the ACCOUNT bucket is what binds. */
    private TokenBucketRateLimiter accountBinds;

    @BeforeEach
    void setUp() {
        // 1 token/second throughout: slow enough that refill does not make these
        // assertions flaky, fast enough that the refill tests need only ~100ms.
        providerBinds = new TokenBucketRateLimiter(true, 1.0, 3, 1.0, 10);
        accountBinds = new TokenBucketRateLimiter(true, 1.0, 10, 1.0, 2);
    }

    @Test
    void allowsUpToTheBurstThenRejects() {
        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));
        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));
        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));

        assertFalse(providerBinds.allow(AiProviderType.OPENAI, null),
                "the fourth call in a burst must be rejected");
    }

    @Test
    void oneAccountCannotDrainAnotherAccountsBucket() {
        // The regression guard: a single tenant must not be able to consume the whole
        // provider allowance.
        assertTrue(accountBinds.allow(AiProviderType.OPENAI, 1L));
        assertTrue(accountBinds.allow(AiProviderType.OPENAI, 1L));
        assertFalse(accountBinds.allow(AiProviderType.OPENAI, 1L), "account 1 exhausted its own burst");

        // A different account is unaffected by account 1's spending.
        assertTrue(accountBinds.allow(AiProviderType.OPENAI, 2L),
                "account 2 must not be penalised for account 1's usage");
    }

    @Test
    void providersAreLimitedIndependently() {
        for (int i = 0; i < 3; i++) {
            assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));
        }
        assertFalse(providerBinds.allow(AiProviderType.OPENAI, null));

        assertTrue(providerBinds.allow(AiProviderType.ANTHROPIC, null),
                "exhausting one provider must not affect another");
    }

    @Test
    void whicheverBucketIsTighterIsTheOneThatBinds() {
        // providerBinds: provider burst 3, account burst 10 -> 3 allowed
        for (int i = 0; i < 3; i++) {
            assertTrue(providerBinds.allow(AiProviderType.OPENAI, 1L));
        }
        assertFalse(providerBinds.allow(AiProviderType.OPENAI, 1L));

        // accountBinds: provider burst 10, account burst 2 -> only 2 allowed
        assertTrue(accountBinds.allow(AiProviderType.OPENAI, 1L));
        assertTrue(accountBinds.allow(AiProviderType.OPENAI, 1L));
        assertFalse(accountBinds.allow(AiProviderType.OPENAI, 1L),
                "the account bucket (2) is tighter here and must bind first");
    }

    @Test
    void aDisabledLimiterAllowsEverything() {
        TokenBucketRateLimiter off = new TokenBucketRateLimiter(false, 1.0, 1, 1.0, 1);

        for (int i = 0; i < 50; i++) {
            assertTrue(off.allow(AiProviderType.OPENAI, 1L),
                    "a disabled limiter must not reject anything");
        }
        assertEquals(-1, off.available(AiProviderType.OPENAI, 1L),
                "a disabled limiter has no opinion on availability");
    }

    @Test
    void aNullAccountFallsBackToTheProviderBucketAlone() {
        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));
        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));
        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null));
        assertFalse(providerBinds.allow(AiProviderType.OPENAI, null));
    }

    @Test
    void aNullProviderIsAllowedRatherThanThrowing() {
        assertTrue(providerBinds.allow(null, 1L), "a null provider must not break the gateway");
    }

    @Test
    void availabilityReflectsRemainingTokens() {
        assertEquals(3, providerBinds.available(AiProviderType.OPENAI, null));

        providerBinds.allow(AiProviderType.OPENAI, null);

        assertTrue(providerBinds.available(AiProviderType.OPENAI, null) < 3,
                "availability must drop after a call is spent");
    }

    @Test
    void theBucketRefillsOverTime() throws Exception {
        TokenBucketRateLimiter slow = new TokenBucketRateLimiter(true, 20.0, 2, 20.0, 2);

        assertTrue(slow.allow(AiProviderType.OPENAI, null));
        assertTrue(slow.allow(AiProviderType.OPENAI, null));
        assertFalse(slow.allow(AiProviderType.OPENAI, null));

        // At 20 tokens/second a full token accrues in ~50ms.
        Thread.sleep(120);

        assertTrue(slow.allow(AiProviderType.OPENAI, null), "the bucket must refill over time");
    }

    @Test
    void aBucketNeverExceedsItsCapacity() throws Exception {
        TokenBucketRateLimiter slow = new TokenBucketRateLimiter(true, 100.0, 3, 100.0, 3);

        Thread.sleep(80);

        assertEquals(3, slow.available(AiProviderType.OPENAI, null),
                "refill must be capped at the burst size, not accumulate without bound");
    }

    @Test
    void aZeroRateDeniesAfterTheBurstAndDoesNotRecover() throws Exception {
        TokenBucketRateLimiter never = new TokenBucketRateLimiter(true, 0.0, 2, 0.0, 2);

        assertTrue(never.allow(AiProviderType.OPENAI, null));
        assertTrue(never.allow(AiProviderType.OPENAI, null));
        assertFalse(never.allow(AiProviderType.OPENAI, null));

        Thread.sleep(60);

        assertFalse(never.allow(AiProviderType.OPENAI, null),
                "a zero rate means no refill; it must not silently become permissive");
    }

    @Test
    void capacityAndRateAreCoercedToSaneValues() {
        TokenBucketRateLimiter.Bucket b = new TokenBucketRateLimiter.Bucket(0, -5.0);

        assertTrue(b.tryAcquire(), "capacity must be at least 1 even if configured as 0");
        assertFalse(b.tryAcquire(), "a negative rate must not refill");
    }

    @Test
    void resetClearsState() {
        providerBinds.allow(AiProviderType.OPENAI, null);
        providerBinds.allow(AiProviderType.OPENAI, null);
        providerBinds.allow(AiProviderType.OPENAI, null);

        providerBinds.reset();

        assertTrue(providerBinds.allow(AiProviderType.OPENAI, null), "reset must restore a full bucket");
    }

    @Test
    void theNoopLimiterStillAllowsEverything() {
        RateLimiter noop = new NoopRateLimiter();

        assertTrue(noop.allow(AiProviderType.OPENAI));
        assertTrue(noop.allow(AiProviderType.OPENAI, 1L));
    }

    @Test
    void theAccountScopedMethodDefaultsToTheProviderScopedOne() {
        RateLimiter providerOnly = new RateLimiter() {
            @Override
            public boolean allow(AiProviderType provider) {
                return false;
            }
        };

        assertFalse(providerOnly.allow(AiProviderType.OPENAI, 1L),
                "a provider-only implementation must stay correct through the account-scoped call");
    }
}
