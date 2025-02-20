// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.inventory;

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hederahashgraph.api.proto.java.Key;
import java.util.function.UnaryOperator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SpecKeyFromMutation extends UtilOp {
    private static final Logger log = LogManager.getLogger(SpecKeyFromMutation.class);

    private final String name;
    private final String mutated;
    private UnaryOperator<Key> mutation = k -> k;

    public SpecKeyFromMutation(String name, String mutated) {
        this.name = name;
        this.mutated = mutated;
    }

    public SpecKeyFromMutation changing(UnaryOperator<Key> mutation) {
        this.mutation = mutation;
        return this;
    }

    @Override
    protected boolean submitOp(HapiSpec spec) throws Throwable {
        var source = spec.registry().getKey(mutated);
        var sink = mutation.apply(source);
        spec.registry().saveKey(name, sink);
        spec.keys().setControl(sink, spec.keys().controlFor(source));
        return false;
    }

    @Override
    protected MoreObjects.ToStringHelper toStringHelper() {
        var helper = super.toStringHelper();
        helper.add("name", name).add("mutated", mutated);
        return helper;
    }
}
