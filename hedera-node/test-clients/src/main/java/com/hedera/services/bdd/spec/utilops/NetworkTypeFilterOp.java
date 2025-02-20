// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.TargetNetworkType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * A {@link UtilOp} that runs a {@link HapiSpecOperation} if the {@link HapiSpec} is configured to run on a
 * {@link TargetNetworkType} that is contained in the {@link Set} of {@link TargetNetworkType} provided to the
 * constructor.
 */
public class NetworkTypeFilterOp extends UtilOp {
    private final Set<TargetNetworkType> allowedNetworkTypes;
    private final SpecOperation[] ops;

    public NetworkTypeFilterOp(
            @NonNull final Set<TargetNetworkType> allowedNetworkTypes, @NonNull final SpecOperation[] ops) {
        this.allowedNetworkTypes = requireNonNull(allowedNetworkTypes);
        this.ops = requireNonNull(ops);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        if (allowedNetworkTypes.contains(spec.targetNetworkType())) {
            allRunFor(spec, ops);
        }
        return false;
    }

    @Override
    public String toString() {
        return "NetworkTypeFilterOp{targets=" + allowedNetworkTypes + "}";
    }
}
