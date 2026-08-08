package com.qalab.qalabai.ai.gateway;

/**
 * Immutable snapshot of an account's token budget for the current billing
 * period, plus the policy that decides how an exhausted allowance is handled.
 *
 * <p>A hard stop applies when {@code remaining <= 0} and the policy is
 * {@link BudgetPolicy#HARD}: the gateway refuses the call with
 * {@code AI_BUDGET_EXCEEDED} and never contacts a provider. With
 * {@link BudgetPolicy#SOFT} the call proceeds but the workflow context is
 * flagged. With {@link BudgetPolicy#NONE} the allowance is never enforced.</p>
 */
public class TokenBudget {

    private final long limit;
    private final long used;
    private final long remaining;
    private final boolean hardStop;
    private final BudgetPolicy policy;

    public TokenBudget(long limit, long used, boolean hardStop) {
        this(limit, used, hardStop, BudgetPolicy.HARD);
    }

    public TokenBudget(long limit, long used, boolean hardStop, BudgetPolicy policy) {
        this.limit = limit;
        this.used = used;
        this.remaining = Math.max(0, limit - used);
        this.policy = policy != null ? policy : BudgetPolicy.HARD;
        this.hardStop = hardStop && policy == BudgetPolicy.HARD && remaining <= 0 && limit > 0;
    }

    public long getLimit() {
        return limit;
    }

    public long getUsed() {
        return used;
    }

    public long getRemaining() {
        return remaining;
    }

    public BudgetPolicy getPolicy() {
        return policy;
    }

    /** Whether further managed calls are blocked (HARD + remaining <= 0). */
    public boolean isHardStop() {
        return hardStop;
    }

    /** Whether the allowance is exhausted regardless of policy. */
    public boolean isExhausted() {
        return remaining <= 0;
    }

    /** Whether a SOFT policy should surface a warning but still allow the call. */
    public boolean isSoftExceeded() {
        return policy == BudgetPolicy.SOFT && isExhausted() && limit > 0;
    }
}
