// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.grouping;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.UtilOp;

public class InBlockingOrder extends UtilOp implements GroupedOps<InBlockingOrder> {
    private final SpecOperation[] ops;

    public InBlockingOrder(SpecOperation... ops) {
        this.ops = ops;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) {
        allRunFor(spec, ops);
        return false;
    }

    public SpecOperation last() {
        return ops[ops.length - 1];
    }

    @Override
    public String toString() {
        return "InBlockingOrder";
    }

    @Override
    public InBlockingOrder failOnErrors() {
        return this;
    }
}
