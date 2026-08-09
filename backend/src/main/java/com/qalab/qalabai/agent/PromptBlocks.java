package com.qalab.qalabai.agent;

import com.qalab.qalabai.util.UserInstructions;

/**
 * Shared prompt-building blocks for the AI agents. Keeps the "USER
 * INSTRUCTIONS" section and the structured test-type constraint identical
 * across operations so behaviour and traceability stay consistent.
 */
public final class PromptBlocks {

    private PromptBlocks() {
    }

    /** The clearly separated USER INSTRUCTIONS section appended to the user prompt. */
    public static String userInstructions(String instruction) {
        if (!UserInstructions.isPresent(instruction)) {
            return "";
        }
        return """
                USER INSTRUCTIONS

                The user provided the following instructions for this operation:

                \"\"\"
                %s
                \"\"\"

                Follow the user instructions when they are compatible with the available
                application evidence and operation constraints. Do not invent unsupported
                functionality.
                """.formatted(instruction);
    }

    /**
     * A deterministic, machine-verifiable constraint line for the structured test
     * type (UI/E2E/API/ALL). Returned empty when no explicit type was requested.
     */
    public static String testTypeConstraint(String testType) {
        if (!UserInstructions.isPresent(testType)) {
            return "";
        }
        String upper = testType.trim().toUpperCase();
        if ("ALL".equals(upper)) {
            return "";
        }
        return """
                STRUCTURED TEST TYPE CONSTRAINT
                =================================
                Generate only %s tests. This is a hard, deterministic constraint; it takes
                precedence over any contradictory wording in the USER INSTRUCTIONS.
                """.formatted(upper);
    }
}
