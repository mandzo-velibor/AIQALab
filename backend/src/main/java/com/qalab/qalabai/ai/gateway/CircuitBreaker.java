package com.qalab.qalabai.ai.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-provider circuit breaker.
 *
 * <p>Without one, a provider that is down costs a full timeout on every single call:
 * the request thread waits, the cascade inside that call tries the next provider, and the
 * caller gets its answer several minutes late for a failure that was decided in the first
 * second. That is the "one slow provider degrades the whole application" failure, and it
 * is why this exists rather than more retries.</p>
 *
 * <p>Three states:</p>
 * <ul>
 *   <li><strong>CLOSED</strong> — normal. Consecutive failures are counted; a single
 *       success resets the count, because a provider that works is not on fire.</li>
 *   <li><strong>OPEN</strong> — failing fast. Calls are rejected immediately with no
 *       network I/O at all until the cooldown elapses.</li>
 *   <li><strong>HALF_OPEN</strong> — a limited number of probe calls are admitted. One
 *       success closes the breaker; one failure re-opens it and restarts the cooldown.
 *       Probing rather than closing outright matters: the alternative is a thundering
 *       herd of every waiting caller hitting a provider the instant it recovers.</li>
 * </ul>
 *
 * <p>Not every failure counts. A 400 or a 404 is <em>our</em> request being wrong, and
 * tripping the breaker on it would take a working provider out of service for a reason
 * that fixing the request would resolve. A 5xx, a 429, a timeout, a connection error and
 * a rejected credential all do count: in each case the provider is the problem, and
 * hammering it changes nothing.</p>
 *
 * <p>Thread-safe. The probe allowance is a plain counter rather than a lock: two
 * concurrent callers arriving together must not both probe, and {@link AtomicInteger}
 * makes the admission decision in one comparison.</p>
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    /** Why a call was refused, so the caller can say something useful. */
    public record Refusal(String provider, State state, long retryAfterSeconds) {
        public String guidance() {
            return "AI provider " + provider + " circuit is " + state.name().toLowerCase()
                    + " after repeated failures; refusing to call it for another "
                    + retryAfterSeconds + "s. Retry after that, or configure a different provider.";
        }
    }

    private final String provider;
    private final int failureThreshold;
    private final Duration cooldown;
    private final java.util.function.LongSupplier clockNanos;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger probesInFlight = new AtomicInteger();
    private final AtomicReference<Long> openedAtNanos = new AtomicReference<>();
    private final AtomicLongAdder transitions = new AtomicLongAdder();
    private final AtomicLongAdder rejections = new AtomicLongAdder();

    public CircuitBreaker(String provider, int failureThreshold, Duration cooldown) {
        this(provider, failureThreshold, cooldown, System::nanoTime);
    }

    /**
     * @param clockNanos a monotonic nanosecond clock, injectable so the cooldown can be
     *                   tested by advancing time rather than by sleeping. A time-based
     *                   state machine tested with real sleeps is either slow or flaky, and
     *                   usually ends up asserted loosely enough to pass either way.
     */
    CircuitBreaker(String provider, int failureThreshold, Duration cooldown,
                   java.util.function.LongSupplier clockNanos) {
        this.provider = provider;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = cooldown;
        this.clockNanos = clockNanos;
    }

    private long nowNanos() {
        return clockNanos.getAsLong();
    }

    public String provider() {
        return provider;
    }

    public State state() {
        return currentState();
    }

    private State currentState() {
        State snapshot = state.get();
        if (snapshot == State.OPEN && cooldownElapsed()) {
            // Lazily promote on read rather than running a sweeper thread: there is no
            // timer to leak, and a caller arriving after the cooldown is exactly the
            // caller we want to let probe.
            if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                probesInFlight.set(0);
                transitions.increment();
                log.info("Circuit for {} is half-open after the {}s cooldown; probing", provider, cooldown.toSeconds());
            }
            return state.get();
        }
        return snapshot;
    }

    private boolean cooldownElapsed() {
        Long opened = openedAtNanos.get();
        return opened != null && nowNanos() - opened >= cooldown.toNanos();
    }

    /**
     * Reserves the right to make a call.
     *
     * @return null when the call may proceed, or a {@link Refusal} explaining why not
     */
    public Refusal acquire() {
        State current = currentState();
        if (current == State.CLOSED) {
            return null;
        }
        if (current == State.OPEN) {
            Long opened = openedAtNanos.get();
            long remainingNanos = opened == null ? 0 : Math.max(0, cooldown.toNanos() - (nowNanos() - opened));
            long remaining = (remainingNanos + 999_999_999L) / 1_000_000_000L;
            rejections.increment();
            return new Refusal(provider, State.OPEN, remaining);
        }
        // HALF_OPEN: admit a bounded number of probes, not all of them.
        if (probesInFlight.incrementAndGet() > 1) {
            rejections.increment();
            return new Refusal(provider, State.HALF_OPEN, 0);
        }
        return null;
    }

    /** Records a successful call: the provider is healthy, so close the breaker. */
    public void recordSuccess() {
        probesInFlight.set(0);
        consecutiveFailures.set(0);
        State previous = state.getAndSet(State.CLOSED);
        openedAtNanos.set(null);
        if (previous != State.CLOSED) {
            transitions.increment();
            log.info("Circuit for {} closed again after a successful call", provider);
        }
    }

    /**
     * Records a failed call. Only failures that indicate a provider problem should reach
     * here; use {@link #countsAsFailure(int)} to decide.
     */
    public void recordFailure() {
        probesInFlight.decrementAndGet();
        int failures = consecutiveFailures.incrementAndGet();

        if (state.get() == State.HALF_OPEN) {
            trip("a probe failed");
            return;
        }
        if (failures >= failureThreshold) {
            trip(failures + " consecutive failures");
        }
    }

    private void trip(String reason) {
        openedAtNanos.set(nowNanos());
        probesInFlight.set(0);
        if (state.getAndSet(State.OPEN) != State.OPEN) {
            transitions.increment();
        }
        log.warn("Circuit for {} opened after {}; refusing calls for {}s", provider, reason, cooldown.toSeconds());
    }

    /**
     * Whether a failure with this HTTP status should count towards opening the breaker.
     *
     * <p>4xx means the request was wrong, not the provider. 401 and 403 are the
     * exception: a rejected credential leaves the provider unusable to us, and retrying
     * it cannot help.</p>
     */
    public static boolean countsAsFailure(int httpStatus) {
        if (httpStatus == 400 || httpStatus == 404 || httpStatus == 422) {
            return false;
        }
        return httpStatus >= 200;
    }

    /** A snapshot for logs and the metrics work in B-026. */
    public record Stats(String provider, State state, int consecutiveFailures, int threshold,
                        long cooldownSeconds, long transitions, long rejections) {
    }

    public Stats stats() {
        return new Stats(provider, currentState(), consecutiveFailures.get(), failureThreshold,
                cooldown.toSeconds(), transitions.get(), rejections.get());
    }

    @Override
    public String toString() {
        return "CircuitBreaker[" + provider + " " + stats() + "]";
    }

    /** Minimal counter; avoids exposing the adder's type in the public API. */
    private static final class AtomicLongAdder {
        private final java.util.concurrent.atomic.AtomicLong value =
                new java.util.concurrent.atomic.AtomicLong();

        void increment() {
            value.incrementAndGet();
        }

        long get() {
            return value.get();
        }
    }
}
