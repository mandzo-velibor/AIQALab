package com.qalab.qalabai.ai.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-provider circuit breaker and concurrency bulkhead.
 *
 * <p>Two different protections, often confused:</p>
 * <ul>
 *   <li>The <strong>breaker</strong> asks "is this provider healthy?". It stops calls to a
 *       provider that is failing, so failures cost nothing instead of a timeout.</li>
 *   <li>The <strong>bulkhead</strong> asks "how much of this process is it allowed to
 *       use?". It bounds how many AI calls can be in flight at once, so one slow provider
 *       cannot occupy every request thread and starve the rest of the application.</li>
 * </ul>
 *
 * <p>Both are per provider, and the key is the provider's own label — which for the
 * managed path is the individual model inside the cascade, not the aggregate
 * {@code AIQALAB} entry. That is what lets a dead model inside a cascade be skipped while
 * its siblings still work.</p>
 *
 * <p>Unlike the rate limiter, the bulkhead is <strong>on by default</strong>. The rate
 * limiter is a cost policy the operator chooses; the bulkhead is a safety limit, and the
 * failure it prevents is thread starvation and unbounded memory rather than an unexpected
 * bill.</p>
 */
@Component
public class ProviderResilience {

    private static final Logger log = LoggerFactory.getLogger(ProviderResilience.class);

    private final int failureThreshold;
    private final Duration cooldown;
    private final int maxConcurrentPerProvider;
    private final Duration acquireTimeout;

    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> bulkheads = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rejections = new ConcurrentHashMap<>();

    public ProviderResilience(
            @Value("${qalab.ai.breaker.failure-threshold:5}") int failureThreshold,
            @Value("${qalab.ai.breaker.cooldown-seconds:30}") long cooldownSeconds,
            @Value("${qalab.ai.bulkhead.max-concurrent:8}") int maxConcurrentPerProvider,
            @Value("${qalab.ai.bulkhead.acquire-timeout-ms:2000}") long acquireTimeoutMs) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.cooldown = Duration.ofSeconds(Math.max(1, cooldownSeconds));
        this.maxConcurrentPerProvider = Math.max(1, maxConcurrentPerProvider);
        this.acquireTimeout = Duration.ofMillis(Math.max(0, acquireTimeoutMs));
        log.info("AI resilience: open after {} failure(s), {}s cooldown, max {} concurrent call(s) per provider",
                this.failureThreshold, this.cooldown.toSeconds(), this.maxConcurrentPerProvider);
    }

    public CircuitBreaker breaker(String provider) {
        return breakers.computeIfAbsent(provider,
                key -> new CircuitBreaker(key, failureThreshold, cooldown));
    }

    /**
     * @return null when the call may proceed, or a refusal explaining what to do about it
     */
    public CircuitBreaker.Refusal checkCircuit(String provider) {
        return breaker(provider).acquire();
    }

    public void recordSuccess(String provider) {
        breaker(provider).recordSuccess();
    }

    public void recordFailure(String provider) {
        breaker(provider).recordFailure();
    }

    /**
     * Waits briefly for a slot rather than failing immediately.
     *
     * <p>A short wait turns a transient burst into a queue instead of a wave of errors;
     * past the timeout, refusing is the right answer, because holding a request thread
     * open for a provider that is not coming back is the failure this prevents.</p>
     *
     * @return a permit to release, or null when the bulkhead is full
     */
    public Permit acquireSlot(String provider) {
        Semaphore semaphore = bulkheads.computeIfAbsent(provider, key -> {
            log.debug("Creating bulkhead for {} with {} permits", key, maxConcurrentPerProvider);
            return new Semaphore(maxConcurrentPerProvider, true);
        });
        try {
            if (semaphore.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return new Permit(provider, semaphore);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        rejections.computeIfAbsent(provider, key -> new AtomicLong()).incrementAndGet();
        log.warn("Bulkhead for {} is full ({} in flight); refusing rather than queueing behind a slow provider",
                provider, maxConcurrentPerProvider);
        return null;
    }

    public long rejections(String provider) {
        AtomicLong counter = rejections.get(provider);
        return counter == null ? 0 : counter.get();
    }

    /** Every breaker's state, for logs and the metrics work in B-026. */
    public java.util.List<CircuitBreaker.Stats> stats() {
        return breakers.values().stream()
                .map(CircuitBreaker::stats)
                .sorted(java.util.Comparator.comparing(CircuitBreaker.Stats::provider))
                .toList();
    }

    /** Must be released in a finally block. */
    public final class Permit implements AutoCloseable {
        private final String provider;
        private final Semaphore semaphore;
        private boolean released;

        private Permit(String provider, Semaphore semaphore) {
            this.provider = provider;
            this.semaphore = semaphore;
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                semaphore.release();
            }
        }
    }
}
