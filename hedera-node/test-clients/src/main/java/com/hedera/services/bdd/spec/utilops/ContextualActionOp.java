// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import com.hedera.services.bdd.spec.HapiSpec;
import java.util.function.Consumer;

public class ContextualActionOp extends UtilOp {
    private final Consumer<HapiSpec> action;

    public ContextualActionOp(Consumer<HapiSpec> action) {
        this.action = action;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        action.accept(spec);
        return false;
    }

    @Override
    public String toString() {
        return "ContextualActionOp";
    }
}
