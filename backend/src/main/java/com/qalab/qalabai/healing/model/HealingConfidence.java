package com.qalab.qalabai.healing.model;

/**
 * Human-readable confidence bands used consistently across the UI, CLI and
 * reports. The numeric value (0.0 - 1.0) is always kept alongside the label.
 */
public enum HealingConfidence {
    HIGH,
    MEDIUM,
    LOW;

    public static HealingConfidence from(double confidence) {
        if (confidence >= 0.90) {
            return HIGH;
        }
        if (confidence >= 0.70) {
            return MEDIUM;
        }
        return LOW;
    }
}
