package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.LocatorHealth;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocatorHealthServiceTest {

    private final LocatorHealthService service = new LocatorHealthService();

    @Test
    void healthyWhenStableAndGoodHistory() {
        assertEquals(LocatorHealth.HEALTHY, service.classify(22.0, 0.95, 5, true));
        assertEquals(LocatorHealth.HEALTHY, service.classify(25.0, 0.80, 1, true));
    }

    @Test
    void warningWhenStableButNoHistory() {
        assertEquals(LocatorHealth.WARNING, service.classify(20.0, null, 0, true));
    }

    @Test
    void fragileWhenLowStabilityOrBadHistory() {
        assertEquals(LocatorHealth.FRAGILE, service.classify(8.0, null, 0, true));
        assertEquals(LocatorHealth.FRAGILE, service.classify(22.0, 0.3, 4, true));
    }

    @Test
    void brokenWhenVeryLowStability() {
        assertEquals(LocatorHealth.BROKEN, service.classify(3.0, 1.0, 1, true));
    }

    @Test
    void brokenWhenElementNotResolved() {
        assertEquals(LocatorHealth.BROKEN, service.classify(25.0, 1.0, 5, false));
    }

    @Test
    void unknownWithoutAnyStabilityEvidence() {
        assertEquals(LocatorHealth.UNKNOWN, service.classify(Double.NaN, null, 0, true));
        assertEquals(LocatorHealth.UNKNOWN, service.classify(-1.0, null, 0, true));
    }
}
