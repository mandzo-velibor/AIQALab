package com.qalab.qalabai.service.workspace;

/**
 * Identifies how a test execution is associated with a target project/workspace.
 *
 * <p>For Sprint 10 only {@link #LOCAL_WORKSPACE} is fully implemented; the
 * abstraction exists so the API can evolve towards remote execution later.</p>
 */
public enum TestExecutionTarget {

    /** Execution runs inside a local workspace provided by the client. */
    LOCAL_WORKSPACE,

    /** Execution is delegated to an external/remote runner. Not implemented yet. */
    REMOTE
}
