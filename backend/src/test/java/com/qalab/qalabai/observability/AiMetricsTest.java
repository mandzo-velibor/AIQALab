package com.qalab.qalabai.observability;

import com.qalab.qalabai.ai.gateway.CircuitBreaker;
import com.qalab.qalabai.ai.gateway.ProviderResilience;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Metrics that are registered but never incremented are indistinguishable from metrics
 * that do not exist, so these assert on the registry rather than on the code that writes
 * to it.
 *
 * <p>They also pin the <em>labels</em>, because the label set is the part that decides
 * whether a metrics backend survives contact with real traffic.</p>
 */
class AiMetricsTest {

    private SimpleMeterRegistry registry;
    private ProviderResilience resilience;
    private AiMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        resilience = new ProviderResilience(2, 30, 8, 10);
        metrics = new AiMetrics(registry, resilience);
    }

    private double counterValue(String name, String... tags) {
        Counter counter = registry.find(name).tags(tags).counter();
        assertNotNull(counter, "no counter named " + name + " with " + List.of(tags));
        return counter.count();
    }

    private double timerCount(String name, String... tags) {
        var timer = registry.find(name).tags(tags).timer();
        assertNotNull(timer, "no timer named " + name + " with " + List.of(tags));
        return timer.count();
    }

    private double timerTotalMillis(String name, String... tags) {
        var timer = registry.find(name).tags(tags).timer();
        assertNotNull(timer, "no timer named " + name + " with " + List.of(tags));
        return timer.totalTime(TimeUnit.MILLISECONDS);
    }

    // ---- ai.calls / ai.latency / ai.tokens / ai.cost ----

    @Test
    void aSuccessfulCallIsCountedAndTimed() {
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "success",
                120, 800, 300, 0.0004, 1);

        assertEquals(1.0, counterValue(AiMetrics.AI_CALLS,
                "provider", "OPENAI", "model", "gpt-4o-mini",
                "operation", "ANALYZE", "outcome", "success"));
        assertEquals(1.0, timerCount(AiMetrics.AI_LATENCY,
                "provider", "OPENAI", "model", "gpt-4o-mini",
                "operation", "ANALYZE", "outcome", "success"));
        assertEquals(120.0, timerTotalMillis(AiMetrics.AI_LATENCY,
                "provider", "OPENAI", "model", "gpt-4o-mini",
                "operation", "ANALYZE", "outcome", "success"), 1.0);
    }

    @Test
    void tokensAreCountedInBothDirections() {
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "success",
                100, 800, 300, 0, 1);

        assertEquals(800.0, counterValue(AiMetrics.AI_TOKENS,
                "provider", "OPENAI", "model", "gpt-4o-mini", "direction", "input"));
        assertEquals(300.0, counterValue(AiMetrics.AI_TOKENS,
                "provider", "OPENAI", "model", "gpt-4o-mini", "direction", "output"));
    }

    @Test
    void failuresAreCountedSeparatelyFromSuccesses() {
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "success", 50, 10, 5, 0, 1);
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "error", 50, 0, 0, 0, 1);
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "http_429", 50, 0, 0, 0, 1);

        assertEquals(1.0, counterValue(AiMetrics.AI_CALLS, "provider", "OPENAI",
                "model", "gpt-4o-mini", "operation", "ANALYZE", "outcome", "success"));
        assertEquals(1.0, counterValue(AiMetrics.AI_CALLS, "provider", "OPENAI",
                "model", "gpt-4o-mini", "operation", "ANALYZE", "outcome", "error"));
        assertEquals(1.0, counterValue(AiMetrics.AI_CALLS, "provider", "OPENAI",
                "model", "gpt-4o-mini", "operation", "ANALYZE", "outcome", "http_429"));
    }

    @Test
    void aMultiAttemptCascadeIsVisible() {
        // The leading indicator of a provider degrading: the cascade needed more than one
        // try, before defect recall drops.
        metrics.recordAiCall("AIQALAB", "space-bunny-free", "BUG_REPORT", "success",
                900, 100, 50, 0, 3);

        assertEquals(2.0, counterValue("qalab.ai.cascade.extra.attempts", "provider", "AIQALAB"),
                "3 attempts means 2 extra tries");
    }

    @Test
    void costIsOnlyRecordedWhenThereIsSome() {
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "success", 10, 10, 5, 0, 1);

        assertTrue(registry.find(AiMetrics.AI_COST).counters().isEmpty(),
                "a free model must not create a zero-valued cost series");
    }

    // ---- playwright + workflow ----

    @Test
    void aPlaywrightRunIsTimedAndItsTestsCounted() {
        metrics.recordPlaywrightRun("FAILED", 18_612, 11, 4);

        assertEquals(1.0, timerCount(AiMetrics.PLAYWRIGHT_DURATION, "status", "FAILED"));
        assertEquals(18_612.0, timerTotalMillis(AiMetrics.PLAYWRIGHT_DURATION, "status", "FAILED"), 1.0);
        assertEquals(11.0, counterValue("qalab.playwright.tests", "status", "total"));
        assertEquals(4.0, counterValue("qalab.playwright.tests", "status", "failed"));
    }

    @Test
    void workflowStepsAreTimedSeparatelyPerStep() {
        // The whole point: "why was it slow?" is answerable by comparing steps, not by
        // reading logs.
        metrics.recordWorkflowStep("EXPLORE", "COMPLETED", 5_000);
        metrics.recordWorkflowStep("TEST_GENERATION", "COMPLETED", 40_000);

        assertEquals(5_000.0, timerTotalMillis(AiMetrics.WORKFLOW_DURATION,
                "step", "EXPLORE", "status", "COMPLETED"), 1.0);
        assertEquals(40_000.0, timerTotalMillis(AiMetrics.WORKFLOW_DURATION,
                "step", "TEST_GENERATION", "status", "COMPLETED"), 1.0);
    }

    // ---- breaker gauges ----

    @Test
    void breakerStateIsPublishedAsAGaugeThatFollowsTheCircuit() {
        resilience.recordFailure("openai");
        resilience.recordFailure("openai");
        metrics.bindCircuitBreakers();

        assertEquals(2.0, gauge(AiMetrics.BREAKER_STATE, "provider", "openai"),
                "2 is OPEN");

        // A gauge, not a pushed value: a circuit can recover between scrapes, and a
        // stale "open" would say the provider is down when it is fine.
        CircuitBreaker breaker = resilience.breaker("openai");
        breaker.recordSuccess();
        metrics.bindCircuitBreakers();

        assertEquals(0.0, gauge(AiMetrics.BREAKER_STATE, "provider", "openai"),
                "0 is CLOSED");
    }

    @Test
    void bulkheadRejectionsArePublishedForAProviderThatHasNeverFailed() {
        // A provider that was only ever rate-limited has a bulkhead and no breaker. If the
        // gauge were driven by breakers alone, this — the case where the bulkhead is doing
        // the work — would be the one thing an operator cannot see.
        for (int i = 0; i < 8; i++) {
            assertNotNull(resilience.acquireSlot("openai"), "slot " + i + " should be free");
        }
        org.junit.jupiter.api.Assertions.assertNull(resilience.acquireSlot("openai"));

        metrics.bindCircuitBreakers();

        assertEquals(1.0, gauge(AiMetrics.BREAKER_REJECTIONS, "provider", "openai"));
        assertEquals(0.0, gauge(AiMetrics.BREAKER_STATE, "provider", "openai"),
                "no failures means the circuit is closed, whatever the bulkhead did");
    }

    private double gauge(String name, String... tags) {
        var gauge = registry.find(name).tags(tags).gauge();
        assertNotNull(gauge, "no gauge named " + name + " with " + List.of(tags));
        return gauge.value();
    }

    // ---- the constraint that shapes all of this ----

    @Test
    void noMetricCarriesAnAccountOrProjectLabel() {
        // Both are unbounded. A label with unbounded values is the standard way to take
        // down a metrics backend, and it is why the "just tag it with the account"
        // instinct is refused rather than quietly added.
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "success", 10, 10, 5, 0, 1);
        metrics.recordPlaywrightRun("PASSED", 100, 1, 0);
        metrics.recordWorkflowStep("EXPLORE", "COMPLETED", 10);

        for (Meter meter : registry.getMeters()) {
            for (io.micrometer.core.instrument.Tag tag : meter.getId().getTags()) {
                String key = tag.getKey().toLowerCase();
                assertTrue(!key.equals("account") && !key.equals("project")
                                && !key.equals("projectid") && !key.equals("user")
                                && !key.equals("email") && !key.equals("url"),
                        "unbounded label " + tag.getKey() + " on " + meter.getId().getName()
                                + "; per-account data belongs in ai_usage_record");
            }
        }
    }

    @Test
    void aNullTagValueBecomesUnknownRatherThanAnEmptySeries() {
        // A null tag is silently dropped by some backends, which splits one series into
        // two and makes a rate wrong rather than absent.
        metrics.recordAiCall(null, null, null, null, 10, 0, 0, 0, 1);

        assertEquals(1.0, counterValue(AiMetrics.AI_CALLS,
                "provider", "unknown", "model", "unknown",
                "operation", "unknown", "outcome", "unknown"));
    }

    @Test
    void aNegativeLatencyCannotProduceANonsenseTimer() {
        metrics.recordAiCall("OPENAI", "gpt-4o-mini", "ANALYZE", "success", -5, 0, 0, 0, 1);

        assertEquals(0.0, timerTotalMillis(AiMetrics.AI_LATENCY,
                "provider", "OPENAI", "model", "gpt-4o-mini",
                "operation", "ANALYZE", "outcome", "success"), 0.0);
    }
}
