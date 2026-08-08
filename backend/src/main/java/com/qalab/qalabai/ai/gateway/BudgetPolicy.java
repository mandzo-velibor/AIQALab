package com.qalab.qalabai.ai.gateway;

/**
 * How the account token allowance is enforced once the managed budget is
 * exhausted.
 *
 * <ul>
 *   <li>{@link #HARD} — the gateway refuses further managed calls with
 *       {@code AI_BUDGET_EXCEEDED} (default for FREE).</li>
 *   <li>{@link #SOFT} — the gateway lets the call through, records the usage and
 *       marks the workflow context as budget-exceeded so orchestrators can
 *       degrade (smaller contexts, fewer retries) instead of failing.</li>
 *   <li>{@link #NONE} — the allowance is not enforced (default for PRO/TEAM).</li>
 * </ul>
 */
public enum BudgetPolicy {

    HARD,
    SOFT,
    NONE;

    /** Default policy for a given subscription plan. */
    public static BudgetPolicy defaultFor(Plan plan) {
        return switch (plan) {
            case FREE -> HARD;
            default -> NONE;
        };
    }
}
