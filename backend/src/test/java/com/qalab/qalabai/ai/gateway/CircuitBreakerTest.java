package com.qalab.qalabai.ai.gateway;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A circuit breaker that is present but never trips is indistinguishable from one that
 * is not there. These tests are about the state machine's behaviour, not its wiring.
 */
class CircuitBreakerTest {

    /** A hand-cranked clock, so the cooldown is tested by advancing time, not sleeping. */
    private final java.util.concurrent.atomic.AtomicLong fakeClock =
            new java.util.concurrent.atomic.AtomicLong();

    private CircuitBreaker breaker(int threshold, long cooldownSeconds) {
        return new CircuitBreaker("openai", threshold, Duration.ofSeconds(cooldownSeconds), fakeClock::get);
    }

    private void advanceSeconds(long seconds) {
        fakeClock.addAndGet(Duration.ofSeconds(seconds).toNanos());
    }

    @Test
    void startsClosedAndAdmitsCalls() {
        CircuitBreaker breaker = breaker(3, 30);

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertNull(breaker.acquire(), "a closed breaker must not refuse anything");
    }

    @Test
    void opensOnlyAtTheThresholdNotBefore() {
        CircuitBreaker breaker = breaker(3, 30);

        breaker.recordFailure();
        assertNull(breaker.acquire(), "one failure must not open it");
        breaker.recordFailure();
        assertNull(breaker.acquire(), "two failures must not open it either");

        breaker.recordFailure();
        assertNotNull(breaker.acquire(), "the third failure opens it");
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
    }

    @Test
    void aSingleSuccessResetsTheFailureCount() {
        // A provider that works is not on fire. Without a reset, failures spread over a
        // long healthy period would eventually add up to an open breaker.
        CircuitBreaker breaker = breaker(3, 30);

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertNull(breaker.acquire(), "the count restarted after the success");
    }

    @Test
    void anOpenBreakerRefusesWithoutAnyNetworkCall() {
        // The entire point: a dead provider must cost nothing, not a timeout.
        CircuitBreaker breaker = breaker(1, 30);
        breaker.recordFailure();

        CircuitBreaker.Refusal refusal = breaker.acquire();

        assertNotNull(refusal);
        assertEquals(CircuitBreaker.State.OPEN, refusal.state());
        assertTrue(refusal.retryAfterSeconds() > 0, "the refusal must say how long to wait");
        assertTrue(refusal.guidance().contains("openai"), refusal.guidance());
        assertTrue(refusal.guidance().contains("Retry"), "and tell the caller what to do");
    }

    @Test
    void itStaysOpenUntilTheCooldownActuallyElapses() {
        CircuitBreaker breaker = breaker(1, 30);
        breaker.recordFailure();
        assertNotNull(breaker.acquire());

        advanceSeconds(29);
        assertNotNull(breaker.acquire(), "one second short of the cooldown it must still refuse");

        advanceSeconds(2);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    void itHalfOpensAfterTheCooldownAndAdmitsExactlyOneProbe() {
        CircuitBreaker breaker = breaker(1, 30);
        breaker.recordFailure();
        assertNotNull(breaker.acquire());

        advanceSeconds(31);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertNull(breaker.acquire(), "one probe is admitted");
        assertNotNull(breaker.acquire(),
                "a second concurrent caller must not also probe, or the herd returns");
    }

    @Test
    void aSuccessfulProbeClosesTheBreaker() {
        CircuitBreaker breaker = breaker(1, 30);
        breaker.recordFailure();
        advanceSeconds(31);
        breaker.acquire();

        breaker.recordSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
        assertNull(breaker.acquire(), "and it admits calls normally again");
    }

    @Test
    void aFailedProbeReopensTheBreakerAndRestartsTheCooldown() {
        CircuitBreaker breaker = breaker(1, 30);
        breaker.recordFailure();
        advanceSeconds(31);
        breaker.acquire();
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());

        breaker.recordFailure();

        assertEquals(CircuitBreaker.State.OPEN, breaker.state(),
                "a provider that failed the probe is not healthy");
        assertNotNull(breaker.acquire(), "and must not be called again straight away");

        // The cooldown restarts from the failed probe, not from the original failure.
        advanceSeconds(20);
        assertNotNull(breaker.acquire(), "the old cooldown must not have expired");
        advanceSeconds(15);
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
    }

    @Test
    void probingDoesNotStampedeARecoveredProvider() {
        // Closing outright on the cooldown would release every waiting caller at the same
        // instant. Half-open admits one; the rest wait for the result.
        CircuitBreaker breaker = breaker(1, 30);
        breaker.recordFailure();
        advanceSeconds(31);
        breaker.state();

        int admitted = 0;
        for (int i = 0; i < 100; i++) {
            if (breaker.acquire() == null) {
                admitted++;
            }
        }

        assertEquals(1, admitted, "exactly one probe, not a hundred");
    }

    // ---- which failures count ----

    @Test
    void aProviderFaultOpensTheBreaker() {
        assertTrue(CircuitBreaker.countsAsFailure(500));
        assertTrue(CircuitBreaker.countsAsFailure(502));
        assertTrue(CircuitBreaker.countsAsFailure(503));
        assertTrue(CircuitBreaker.countsAsFailure(429), "rate limited means it cannot serve us");
    }

    @Test
    void ourOwnBadRequestDoesNotOpenTheBreaker() {
        // A 400 is our request being wrong. Tripping on it would take a working provider
        // out of service for something fixing the request would resolve.
        assertFalse(CircuitBreaker.countsAsFailure(400));
        assertFalse(CircuitBreaker.countsAsFailure(404));
        assertFalse(CircuitBreaker.countsAsFailure(422));
    }

    @Test
    void aRejectedCredentialDoesOpenTheBreaker() {
        // The exception to the 4xx rule: retrying a rejected credential cannot help, and
        // it is exactly the case where a breaker saves a pointless retry storm.
        assertTrue(CircuitBreaker.countsAsFailure(401));
        assertTrue(CircuitBreaker.countsAsFailure(403));
    }

    // ---- observability ----

    @Test
    void statsExposeTheStateForMetrics() {
        // B-026 turns these into real metrics; the data has to exist first.
        CircuitBreaker breaker = breaker(2, 45);
        breaker.recordFailure();
        breaker.recordFailure();
        assertNotNull(breaker.acquire());

        CircuitBreaker.Stats stats = breaker.stats();

        assertEquals("openai", stats.provider());
        assertEquals(CircuitBreaker.State.OPEN, stats.state());
        assertEquals(2, stats.consecutiveFailures());
        assertEquals(2, stats.threshold());
        assertEquals(45, stats.cooldownSeconds());
        assertTrue(stats.transitions() >= 1, "opening is a transition");
        assertTrue(stats.rejections() >= 1, "refusing a call is a rejection");
    }

    @Test
    void aMisconfiguredThresholdIsClampedRatherThanNeverTripping() {
        CircuitBreaker breaker = breaker(0, 30);

        breaker.recordFailure();

        assertNotNull(breaker.acquire(), "a threshold of 0 must not mean 'never open'");
    }

    // ---- concurrency ----

    @Test
    void concurrentFailuresStillOpenTheBreakerExactlyOnce() throws Exception {
        CircuitBreaker breaker = breaker(5, 30);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger refusals = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int n = 0; n < 20; n++) {
                        if (breaker.acquire() != null) {
                            refusals.incrementAndGet();
                        }
                        breaker.recordFailure();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "workers should finish");

        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertTrue(refusals.get() > 0, "once open, calls must be refused");
        assertEquals(1, breaker.stats().transitions(),
                "32 threads hammering it must not open it 32 times");
    }
}
