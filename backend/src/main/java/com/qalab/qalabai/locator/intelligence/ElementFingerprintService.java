package com.qalab.qalabai.locator.intelligence;

import com.qalab.qalabai.locator.intelligence.model.ElementIdentity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Deterministic element fingerprinting. Only stable identity signals are hashed
 * (tag, role, accessible name, test id, label, placeholder, name, id, href);
 * volatile signals such as CSS classes and inline styles are excluded so the
 * fingerprint survives most refactorings. Two fingerprints are compared with a
 * weighted similarity.
 */
@Component
public class ElementFingerprintService {

    private static final double EMPTY_WEIGHT = 0.0;
    private static final double TAG_WEIGHT = 0.10;
    private static final double ROLE_WEIGHT = 0.15;
    private static final double NAME_WEIGHT = 0.25;
    private static final double TEST_ID_WEIGHT = 0.25;
    private static final double LABEL_WEIGHT = 0.15;
    private static final double PLACEHOLDER_WEIGHT = 0.05;
    private static final double NAME_ATTR_WEIGHT = 0.05;
    private static final double ID_WEIGHT = 0.05;

    /** 64 hex chars; prefix distinguishes it from other ids. */
    public String fingerprint(ElementIdentity identity) {
        if (identity == null) {
            return null;
        }
        String payload = String.join("|",
                norm(identity.tag()),
                norm(identity.role()),
                norm(identity.accessibleName()),
                norm(identity.testId()),
                norm(identity.label()),
                norm(identity.placeholder()),
                norm(identity.name()),
                norm(identity.id()),
                norm(identity.href()));
        return "fp-" + sha256(payload);
    }

    /** Weighted similarity in [0,1] between two element identities. */
    public double matchConfidence(ElementIdentity a, ElementIdentity b) {
        if (a == null || b == null) {
            return 0.0;
        }
        double totalWeight = 0.0;
        double matchedWeight = 0.0;

        totalWeight += TAG_WEIGHT;
        if (both(a.tag(), b.tag())) {
            matchedWeight += TAG_WEIGHT;
        } else if (onlyOne(a.tag(), b.tag())) {
            matchedWeight += TAG_WEIGHT * 0.5;
        }

        totalWeight += ROLE_WEIGHT;
        if (both(a.role(), b.role())) {
            matchedWeight += ROLE_WEIGHT;
        } else if (onlyOne(a.role(), b.role())) {
            matchedWeight += ROLE_WEIGHT * 0.5;
        }

        totalWeight += NAME_WEIGHT;
        matchedWeight += NAME_WEIGHT * attrMatch(a.accessibleName(), b.accessibleName());

        totalWeight += TEST_ID_WEIGHT;
        matchedWeight += TEST_ID_WEIGHT * attrMatch(a.testId(), b.testId());

        totalWeight += LABEL_WEIGHT;
        matchedWeight += LABEL_WEIGHT * attrMatch(a.label(), b.label());

        totalWeight += PLACEHOLDER_WEIGHT;
        matchedWeight += PLACEHOLDER_WEIGHT * attrMatch(a.placeholder(), b.placeholder());

        totalWeight += NAME_ATTR_WEIGHT;
        matchedWeight += NAME_ATTR_WEIGHT * attrMatch(a.name(), b.name());

        totalWeight += ID_WEIGHT;
        matchedWeight += ID_WEIGHT * attrMatch(a.id(), b.id());

        if (totalWeight == 0.0) {
            return 0.0;
        }
        return Math.min(1.0, matchedWeight / totalWeight);
    }

    private double attrMatch(String a, String b) {
        if (both(a, b)) {
            return 1.0;
        }
        if (onlyOne(a, b)) {
            return 0.5;
        }
        boolean aEmpty = a == null || a.isBlank();
        boolean bEmpty = b == null || b.isBlank();
        if (aEmpty && bEmpty) {
            return 1.0;
        }
        return EMPTY_WEIGHT;
    }

    private boolean both(String a, String b) {
        return a != null && !a.isBlank() && b != null && !b.isBlank()
                && norm(a).equals(norm(b));
    }

    private boolean onlyOne(String a, String b) {
        boolean aEmpty = a == null || a.isBlank();
        boolean bEmpty = b == null || b.isBlank();
        return aEmpty != bEmpty;
    }

    private String norm(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
