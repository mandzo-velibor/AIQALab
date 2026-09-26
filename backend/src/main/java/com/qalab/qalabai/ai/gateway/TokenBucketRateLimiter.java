package com.qalab.qalabai.ai.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter, per provider and per account.
 *
 * <p>Replaces {@link NoopRateLimiter}, whose own javadoc admitted it was a placeholder.
 * With no limiter and, before B-013, no authentication, anyone who could reach the
 * port could drive unbounded paid LLM traffic.</p>
 *
 * <p>A bucket holds {@code burst} tokens and refills at {@code ratePerSecond}. Allowing
 * a call spends one token. That shape is deliberate: LLM calls are bursty (a workflow
 * fires several in quick succession) but bounded in aggregate, and a strict
 * requests-per-second limit would reject legitimate bursts while a leaky bucket alone
 * would not bound cost.</p>
 *
 * <p>State is in memory and per-process. That is honest for a single-node deployment,
 * which is what this is; a multi-node deployment would need a shared store, and that
 * limitation is recorded rather than hidden.</p>
 */
@Component
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    private final boolean enabled;
    private final double providerRatePerSecond;
    private final int providerBurst;
    private final double accountRatePerSecond;
    private final int accountBurst;

    private final Map<String, Bucket> providerBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> accountBuckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(
            @Value("${qalab.ai.rate-limit-enabled:false}") boolean enabled,
            @Value("${qalab.ai.rate-limit-provider-rps:2.0}") double providerRatePerSecond,
            @Value("${qalab.ai.rate-limit-provider-burst:10}") int providerBurst,
            @Value("${qalab.ai.rate-limit-account-rps:1.0}") double accountRatePerSecond,
            @Value("${qalab.ai.rate-limit-account-burst:5}") int accountBurst) {
        this.enabled = enabled;
        this.providerRatePerSecond = providerRatePerSecond;
        this.providerBurst = providerBurst;
        this.accountRatePerSecond = accountRatePerSecond;
        this.accountBurst = accountBurst;
        log.info("Rate limiter: enabled={} provider={}rps/{}burst account={}rps/{}burst",
                enabled, providerRatePerSecond, providerBurst, accountRatePerSecond, accountBurst);
    }

    @Override
    public boolean allow(AiProviderType provider) {
        return allow(provider, null);
    }

    @Override
    public boolean allow(AiProviderType provider, Long accountId) {
        if (!enabled || provider == null) {
            return true;
        }
        if (!bucketFor(providerBuckets, "provider:" + provider.name(), providerRatePerSecond, providerBurst)
                .tryAcquire()) {
            log.warn("Rate limit hit for provider {} ({}rps, burst {})", provider, providerRatePerSecond, providerBurst);
            return false;
        }
        if (accountId != null
                && !bucketFor(accountBuckets, "account:" + accountId, accountRatePerSecond, accountBurst)
                .tryAcquire()) {
            log.warn("Rate limit hit for account {} ({}rps, burst {})",
                    accountId, accountRatePerSecond, accountBurst);
            return false;
        }
        return true;
    }

    @Override
    public int available(AiProviderType provider, Long accountId) {
        if (!enabled || provider == null) {
            return -1;
        }
        int providerTokens = providerBuckets
                .getOrDefault("provider:" + provider.name(), new Bucket(providerBurst, providerRatePerSecond))
                .available();
        if (accountId == null) {
            return providerTokens;
        }
        return Math.min(providerTokens, accountBuckets
                .getOrDefault("account:" + accountId, new Bucket(accountBurst, accountRatePerSecond))
                .available());
    }

    private Bucket bucketFor(Map<String, Bucket> buckets, String key, double rate, int burst) {
        return buckets.computeIfAbsent(key, k -> new Bucket(burst, rate));
    }

    /** Test seam: drop all state so each test starts from a full bucket. */
    void reset() {
        providerBuckets.clear();
        accountBuckets.clear();
    }

    /**
     * A single token bucket. Not thread-safe on its own; guarded by the
     * ConcurrentHashMap value replacement and by {@code synchronized} on tryAcquire.
     */
    static final class Bucket {

        private final int capacity;
        private final double ratePerSecond;
        private double tokens;
        private long lastRefillNanos;

        Bucket(int capacity, double ratePerSecond) {
            this.capacity = Math.max(1, capacity);
            this.ratePerSecond = Math.max(0.0, ratePerSecond);
            this.tokens = this.capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized int available() {
            refill();
            return (int) Math.floor(tokens);
        }

        private void refill() {
            long now = System.nanoTime();
            long elapsed = now - lastRefillNanos;
            if (elapsed <= 0) {
                return;
            }
            lastRefillNanos = now;
            if (ratePerSecond <= 0) {
                // A zero rate means "no refill", i.e. burst calls then permanently denied.
                return;
            }
            double replenished = (elapsed / 1_000_000_000.0) * ratePerSecond;
            tokens = Math.min(capacity, tokens + replenished);
        }
    }
}
