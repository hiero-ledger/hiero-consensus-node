// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.services.bdd.spec.HapiSpec;

/**
 * A util op that simple runs a runnable.
 */
public class RunnableOp extends UtilOp {
    private final Runnable runnable;

    public RunnableOp(Runnable runnable) {
        this.runnable = runnable;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        runnable.run();
        return false;
    }

    @Override
    public String toString() {
        return "RunnableOp";
    }
}
