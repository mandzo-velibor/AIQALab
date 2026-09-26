package com.qalab.qalabai.observability;

import com.qalab.qalabai.ai.gateway.CircuitBreaker;
import com.qalab.qalabai.ai.gateway.ProviderResilience;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The metrics that answer "why was it slow?" and "what is this costing?".
 *
 * <p>Before this, the only visibility into an AI call was one INFO line. There was no
 * aggregate, so the only way to know whether a change made things faster was to read logs
 * and count — which is unavailable in production log storage and impossible to alert on.
 * The caching decision Sprint 3 needs was blocked on exactly this.</p>
 *
 * <p><strong>Cardinality is the constraint that shapes everything here.</strong> A metrics
 * backend melts under high-cardinality label sets, so the tag values are deliberately
 * limited to bounded vocabularies: provider, model, operation, outcome, step. The obvious
 * "just tag it with the account" is what turns a working dashboard into an outage, and it
 * is why there is no account or project label — see {@link #ACCOUNT_LABELLING}.</p>
 */
@Component
public class AiMetrics {

    private static final Logger log = LoggerFactory.getLogger(AiMetrics.class);

    public static final String AI_CALLS = "qalab.ai.calls";
    public static final String AI_LATENCY = "qalab.ai.latency";
    public static final String AI_TOKENS = "qalab.ai.tokens";
    public static final String AI_COST = "qalab.ai.cost";
    public static final String PLAYWRIGHT_DURATION = "qalab.playwright.duration";
    public static final String WORKFLOW_DURATION = "qalab.workflow.duration";
    public static final String BREAKER_STATE = "qalab.ai.circuit.state";
    public static final String BREAKER_REJECTIONS = "qalab.ai.circuit.rejections";

    /** Documented, deliberately absent. */
    static final String ACCOUNT_LABELLING =
            "No account/project label: both are unbounded, and a label with unbounded "
                    + "values is the standard way to take down a metrics backend. Per-account "
                    + "cost is already queryable from the ai_usage_record table, which is a "
                    + "better fit for it anyway.";

    private final MeterRegistry registry;
    private final ProviderResilience resilience;
    private final ConcurrentMap<String, AtomicLong> breakerGauge = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> rejectionGauge = new ConcurrentHashMap<>();

    public AiMetrics(MeterRegistry registry, ProviderResilience resilience) {
        this.registry = registry;
        this.resilience = resilience;
    }

    /**
     * Records one AI call.
     *
     * <p>Latency and outcome go together on purpose: a timer without an outcome label
     * averages a fast failure in with a slow success and tells you nothing.</p>
     *
     * @param attempts upstream calls the cascade made; 1 for a single provider
     */
    public void recordAiCall(String provider, String model, String operation,
                             String outcome, long latencyMs, int inputTokens, int outputTokens,
                             double costUsd, int attempts) {
        String safeProvider = orUnknown(provider);
        String safeModel = orUnknown(model);
        String safeOperation = orUnknown(operation);
        String safeOutcome = orUnknown(outcome);

        Tags tags = Tags.of("provider", safeProvider, "model", safeModel,
                "operation", safeOperation, "outcome", safeOutcome);
        registry.counter(AI_CALLS, tags).increment();
        registry.timer(AI_LATENCY, tags).record(Duration.ofMillis(Math.max(0, latencyMs)));
        if (inputTokens > 0 || outputTokens > 0) {
            registry.counter(AI_TOKENS, "provider", safeProvider, "model", safeModel,
                    "direction", "input").increment(inputTokens);
            registry.counter(AI_TOKENS, "provider", safeProvider, "model", safeModel,
                    "direction", "output").increment(outputTokens);
        }
        if (costUsd > 0) {
            registry.counter(AI_COST, "provider", safeProvider, "model", safeModel).increment(costUsd);
        }
        if (attempts > 1) {
            // A count of how often the cascade had to try more than one provider: the
            // leading indicator of a provider degrading, before recall drops.
            registry.counter("qalab.ai.cascade.extra.attempts", "provider", safeProvider)
                    .increment(attempts - 1L);
        }
    }

    public void recordPlaywrightRun(String status, long durationMs, int tests, int failed) {
        Tags tags = Tags.of("status", orUnknown(status));
        registry.timer(PLAYWRIGHT_DURATION, tags).record(Duration.ofMillis(Math.max(0, durationMs)));
        if (tests > 0) {
            registry.counter("qalab.playwright.tests", "status", "total").increment(tests);
        }
        if (failed > 0) {
            Counter failures = registry.counter("qalab.playwright.tests", "status", "failed");
            failures.increment(failed);
        }
    }

    /** @param step the workflow step, e.g. EXPLORE, GENERATE_TESTS, RUN_TESTS */
    public void recordWorkflowStep(String step, String status, long durationMs) {
        registry.timer(WORKFLOW_DURATION,
                        Tags.of("step", orUnknown(step), "status", orUnknown(status)))
                .record(Duration.ofMillis(Math.max(0, durationMs)));
    }

    /**
     * Publishes breaker state as a gauge, re-read on every scrape.
     *
     * <p>A supplier, not an event: a circuit can half-open and close again between scrapes,
     * and a pushed value would go stale and say "open" when the provider is healthy.</p>
     */
    public void bindCircuitBreakers() {
        // Every known provider, not only those with a breaker: a provider that was only
        // ever rate-limited has a bulkhead and no breaker, and its rejections are exactly
        // the thing an operator needs to see.
        java.util.Map<String, CircuitBreaker.State> states = new java.util.LinkedHashMap<>();
        for (CircuitBreaker.Stats stats : resilience.stats()) {
            states.put(stats.provider(), stats.state());
        }
        for (String provider : resilience.knownProviders()) {
            CircuitBreaker.State current = states.getOrDefault(provider, CircuitBreaker.State.CLOSED);
            AtomicLong state = breakerGauge.computeIfAbsent(provider, key -> {
                AtomicLong holder = new AtomicLong();
                registry.gauge(BREAKER_STATE, Tags.of("provider", key),
                        holder, AtomicLong::doubleValue);
                return holder;
            });
            state.set((long) stateValue(current));

            AtomicLong rejections = rejectionGauge.computeIfAbsent(provider, key -> {
                AtomicLong holder = new AtomicLong();
                registry.gauge(BREAKER_REJECTIONS, Tags.of("provider", key),
                        holder, AtomicLong::doubleValue);
                return holder;
            });
            rejections.set(resilience.rejections(provider));
        }
    }

    /**
     * Called after each call so the gauge reflects the live state without waiting for a
     * scrape to notice.
     */
    public void refreshCircuitBreakers() {
        bindCircuitBreakers();
    }

    private static double stateValue(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
        };
    }

    /**
     * The live instance, or null when metrics are not configured.
     *
     * <p>Static access is a compromise, and a deliberate one: metrics are useful from
     * places that have no business holding a metrics dependency injected — a tool built
     * by hand in a test, a static utility. Everything that <em>can</em> take a constructor
     * does (the gateway), and this is the fallback for the rest.</p>
     */
    private static volatile AiMetrics instance;

    public static AiMetrics current() {
        return instance;
    }

    @jakarta.annotation.PostConstruct
    void publish() {
        instance = this;
        // Counters and timers are registered lazily by Micrometer, which is correct — an
        // empty series is noise. But it means a fresh instance shows nothing at all, and
        // an operator cannot tell "no traffic yet" from "metrics are not wired up". The
        // breaker gauges are bound eagerly so circuit state is visible before the first
        // failure, which is when it is most useful.
        bindCircuitBreakers();
    }

    private static String orUnknown(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
