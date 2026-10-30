// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec;

/**
 * Tracks the nesting depth of contract executions on the current thread.
 */
public final class ContractExecutionDepth {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private ContractExecutionDepth() {}

    /**
     * Marks the start of a contract execution on this thread.
     */
    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    /**
     * Marks the end of a contract execution on this thread.
     */
    public static void exit() {
        final int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
    }

    /**
     * Returns whether the current contract execution is nested within another contract execution.
     *
     * @return true if nested within another contract execution
     */
    public static boolean isNested() {
        return DEPTH.get() > 1;
    }
}
