// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import java.util.function.Supplier;

public class SourcedOp extends UtilOp {
    private final Supplier<HapiSpecOperation> source;

    public SourcedOp(Supplier<HapiSpecOperation> source) {
        this.source = source;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        allRunFor(spec, source.get());
        return false;
    }

    @Override
    public String toString() {
        return "SourcedOp";
    }
}
