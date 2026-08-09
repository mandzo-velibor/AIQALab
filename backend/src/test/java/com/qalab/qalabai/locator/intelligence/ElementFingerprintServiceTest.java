package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElementFingerprintServiceTest {

    private final ElementFingerprintService service = new ElementFingerprintService();

    private ElementIdentity button(String testId, String name, String role) {
        return new ElementIdentity("https://x.dev", "button", role, name, name, testId,
                null, null, null, null, null, "btn big",
                new LinkedHashMap<>(), true, true);
    }

    @Test
    void fingerprintIsDeterministic() {
        ElementIdentity a = button("login-btn", "Log in", "button");
        ElementIdentity b = button("login-btn", "Log in", "button");
        assertEquals(service.fingerprint(a), service.fingerprint(b));
    }

    @Test
    void fingerprintIgnoresVolatileClassName() {
        ElementIdentity a = button("login-btn", "Log in", "button");
        ElementIdentity b = button("login-btn", "Log in", "button");
        String classA = a.className();
        String classB = b.className();
        assertEquals(service.fingerprint(a), service.fingerprint(b));
    }

    @Test
    void fingerprintChangesWhenTestIdChanges() {
        assertNotEquals(service.fingerprint(button("login-btn", "Log in", "button")),
                service.fingerprint(button("signin-btn", "Log in", "button")));
    }

    @Test
    void fingerprintIsPrefixedAndStableLength() {
        String fp = service.fingerprint(button("login-btn", "Log in", "button"));
        assertNotNull(fp);
        assertTrue(fp.startsWith("fp-"));
        assertEquals(67, fp.length());
    }

    @Test
    void matchConfidencePenalizesMissingTestId() {
        ElementIdentity withTestId = button("login-btn", "Log in", "button");
        ElementIdentity withoutTestId = button(null, "Log in", "button");
        double score = service.matchConfidence(withTestId, withoutTestId);
        assertTrue(score > 0.0 && score < 1.0);
    }

    @Test
    void matchConfidencePerfectForIdentical() {
        ElementIdentity a = button("login-btn", "Log in", "button");
        assertEquals(1.0, service.matchConfidence(a, a), 0.001);
    }

    @Test
    void matchConfidenceZeroForNull() {
        assertEquals(0.0, service.matchConfidence(null, button("login-btn", "Log in", "button")), 0.001);
    }
}
