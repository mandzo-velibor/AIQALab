package com.qalab.qalabai.ai.gateway;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bulkhead and the breaker together are what stop one slow provider from degrading
 * the whole application. Individually they are easy to get subtly wrong: a breaker that
 * never opens, or a bulkhead that queues without bound, looks identical in a test that
 * only exercises the happy path.
 */
class ProviderResilienceTest {

    private ProviderResilience resilience(int threshold, long cooldownSeconds, int maxConcurrent,
                                          long acquireTimeoutMs) {
        return new ProviderResilience(threshold, cooldownSeconds, maxConcurrent, acquireTimeoutMs);
    }

    // ---- bulkhead ----

    @Test
    void theBulkheadCapsConcurrencyAndThenRefuses() throws Exception {
        ProviderResilience resilience = resilience(5, 30, 2, 10);
        ProviderResilience.Permit first = resilience.acquireSlot("openai");
        ProviderResilience.Permit second = resilience.acquireSlot("openai");
        assertNotNull(first);
        assertNotNull(second);

        // The third has nowhere to go. Refusing is the point: queueing without bound
        // would hold a request thread open behind a provider that is not coming back.
        assertNull(resilience.acquireSlot("openai"), "a full bulkhead must refuse");
    }

    @Test
    void releasingAPermitLetsTheNextCallerIn() {
        ProviderResilience resilience = resilience(5, 30, 1, 10);
        ProviderResilience.Permit only = resilience.acquireSlot("openai");
        assertNull(resilience.acquireSlot("openai"));

        only.close();

        assertNotNull(resilience.acquireSlot("openai"), "the slot must be reusable");
    }

    @Test
    void closingAPermitTwiceDoesNotInflateTheBulkhead() {
        // A double close would hand out capacity that does not exist, which is worse than
        // leaking it: the limit is the only thing holding.
        ProviderResilience resilience = resilience(5, 30, 1, 10);
        ProviderResilience.Permit permit = resilience.acquireSlot("openai");
        permit.close();
        permit.close();

        assertNotNull(resilience.acquireSlot("openai"));
        assertNull(resilience.acquireSlot("openai"), "still only one permit exists");
    }

    @Test
    void theBulkheadIsPerProvider() {
        // One saturated provider must not consume another's capacity.
        ProviderResilience resilience = resilience(5, 30, 1, 10);
        assertNotNull(resilience.acquireSlot("openai"));

        assertNotNull(resilience.acquireSlot("anthropic"),
                "a different provider has its own capacity");
    }

    @Test
    void aFullBulkheadIsCountedSoItIsVisible() {
        ProviderResilience resilience = resilience(5, 30, 1, 10);
        resilience.acquireSlot("openai");
        assertNull(resilience.acquireSlot("openai"));

        assertEquals(1, resilience.rejections("openai"),
                "refusals must be countable, or this is invisible in production");
    }

    @Test
    void concurrentCallersNeverExceedTheCap() throws Exception {
        int cap = 4;
        int callers = 40;
        ProviderResilience resilience = resilience(5, 30, cap, 50);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);

        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ProviderResilience.Permit permit = resilience.acquireSlot("openai");
                    if (permit == null) {
                        return;
                    }
                    try {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        Thread.sleep(5);
                    } finally {
                        inFlight.decrementAndGet();
                        permit.close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(peak.get() <= cap,
                "saw " + peak.get() + " concurrent calls with a cap of " + cap);
    }

    @Test
    void aMisconfiguredCapIsClampedRatherThanDeadlocking() {
        ProviderResilience resilience = resilience(5, 30, 0, 10);

        assertNotNull(resilience.acquireSlot("openai"),
                "a cap of 0 must not mean 'never admit anything'");
    }

    // ---- breaker wiring through the registry ----

    @Test
    void breakersAreHeldPerProvider() {
        ProviderResilience resilience = resilience(1, 30, 5, 10);
        resilience.recordFailure("openai");

        assertNotNull(resilience.checkCircuit("openai"), "openai should be open");
        assertNull(resilience.checkCircuit("anthropic"), "anthropic is untouched");
    }

    @Test
    void theSameBreakerInstanceIsReturnedForTheSameProvider() {
        ProviderResilience resilience = resilience(5, 30, 5, 10);

        assertTrue(resilience.breaker("openai") == resilience.breaker("openai"),
                "state must be shared, or each caller gets a fresh closed breaker");
    }

    @Test
    void aSuccessfulCallClosesTheBreaker() {
        ProviderResilience resilience = resilience(1, 30, 5, 10);
        resilience.recordFailure("openai");
        assertNotNull(resilience.checkCircuit("openai"));

        resilience.recordSuccess("openai");

        assertNull(resilience.checkCircuit("openai"));
    }

    @Test
    void statsCoverEveryProviderTheGatewayHasCalled() {
        ProviderResilience resilience = resilience(2, 30, 5, 10);
        resilience.recordFailure("openai");
        resilience.recordFailure("openai");
        resilience.recordFailure("anthropic");

        List<CircuitBreaker.Stats> stats = resilience.stats();

        assertEquals(2, stats.size());
        assertTrue(stats.stream().anyMatch(s -> s.provider().equals("openai")
                && s.state() == CircuitBreaker.State.OPEN));
        assertTrue(stats.stream().anyMatch(s -> s.provider().equals("anthropic")
                && s.state() == CircuitBreaker.State.CLOSED));
    }
}
