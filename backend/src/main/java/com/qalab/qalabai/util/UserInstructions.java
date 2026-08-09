package com.qalab.qalabai.util;

/**
 * Deterministic normalization of user-provided instructions.
 *
 * <p>An empty, whitespace-only or null instruction behaves exactly like
 * "no instruction was provided" (returns {@code null}). Excessive inner
 * whitespace is collapsed so prompts stay compact, but the instruction is
 * always treated as untrusted input: it is task context, never a system prompt.
 */
public final class UserInstructions {

    private UserInstructions() {
    }

    /** @return the normalized instruction, or {@code null} when it is blank. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String collapsed = raw.trim().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? null : collapsed;
    }

    /** Whether a normalized instruction is actually present. */
    public static boolean isPresent(String instruction) {
        return instruction != null && !instruction.isBlank();
    }
}
