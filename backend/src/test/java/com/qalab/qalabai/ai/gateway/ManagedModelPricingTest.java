package com.qalab.qalabai.ai.gateway;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Zen primary model is now Space Bunny Free.
 *
 * <p>Both managed models are free-tier, so the per-token price is zero — but an
 * unregistered model returns a {@code null} cost, which is indistinguishable from "we
 * forgot to price this" and quietly breaks cost reporting for whichever model the
 * cascade reaches most often.</p>
 */
class ManagedModelPricingTest {

    private final ProviderPricingRegistry registry = new ProviderPricingRegistry();

    @Test
    void theZenModelsArePricedRatherThanUnknown() {
        assertNotNull(registry.lookup(AiProviderType.OPENCODE, "space-bunny-free"),
                "the Zen primary must be priced, not unknown");
        assertNotNull(registry.lookup(AiProviderType.OPENCODE, "big-pickle"),
                "the Zen fallback must be priced, not unknown");
    }

    @Test
    void managedModelsCostNothingButReportAValue() {
        BigDecimal cost = registry.estimateCost(AiProviderType.OPENCODE, "space-bunny-free", 100_000, 50_000);
        assertNotNull(cost, "a known free model must report zero, not null");
        assertEquals(0, cost.compareTo(BigDecimal.ZERO));
        assertTrue(cost.signum() == 0);
    }

    @Test
    void anUnknownModelStillReportsNull() {
        // The distinction that makes the previous test meaningful: null is reserved for
        // genuinely unknown models, so it keeps its diagnostic value.
        assertEquals(null, registry.estimateCost(AiProviderType.OPENCODE, "no-such-model", 10, 10));
    }
}
