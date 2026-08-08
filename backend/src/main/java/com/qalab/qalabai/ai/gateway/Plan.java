package com.qalab.qalabai.ai.gateway;

/**
 * Account subscription plan. Only FREE is implemented in this sprint; PRO and
 * TEAM are reserved for future billing work.
 */
public enum Plan {

    FREE(4000L),
    PRO(0L),
    TEAM(0L);

    private final long defaultMonthlyTokenLimit;

    Plan(long defaultMonthlyTokenLimit) {
        this.defaultMonthlyTokenLimit = defaultMonthlyTokenLimit;
    }

    public long getDefaultMonthlyTokenLimit() {
        return defaultMonthlyTokenLimit;
    }
}
