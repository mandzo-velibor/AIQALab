package com.qalab.qalabai;

import org.hibernate.dialect.H2Dialect;

/**
 * Disables {@code INSERT ... RETURNING} which H2 does not support, so tests run
 * on H2 the way production PostgreSQL does.
 */
public class NoInsertReturningH2Dialect extends H2Dialect {

    @Override
    public boolean supportsInsertReturning() {
        return false;
    }
}
