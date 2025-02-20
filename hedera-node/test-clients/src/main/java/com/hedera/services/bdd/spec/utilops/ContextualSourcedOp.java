// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.function.Function;

public class ContextualSourcedOp extends UtilOp {
    private final Function<HapiSpec, SpecOperation> source;

    public ContextualSourcedOp(Function<HapiSpec, SpecOperation> source) {
        this.source = source;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        allRunFor(spec, source.apply(spec));
        return false;
    }

    @Override
    public String toString() {
        return "ContextualSourcedOp";
    }
}
