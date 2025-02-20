// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops;

import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static java.util.Objects.requireNonNull;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.TargetNetworkType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * A {@link UtilOp} that runs a {@link HapiSpecOperation} if the {@link HapiSpec} is configured to run on a
 * {@link TargetNetworkType} that is contained in the {@link Set} of {@link TargetNetworkType} provided to the
 * constructor.
 */
public class EnvFilterOp extends UtilOp {
    public enum EnvType {
        CI,
        NOT_CI
    }

    private final EnvType allowedEnvType;

    private final HapiSpecOperation[] ops;

    public EnvFilterOp(@NonNull final EnvType allowedEnvType, @NonNull final HapiSpecOperation[] ops) {
        this.allowedEnvType = requireNonNull(allowedEnvType);
        this.ops = requireNonNull(ops);
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        requireNonNull(spec);
        final var inCi = System.getenv("CI") != null;
        final var targetsCi = allowedEnvType == EnvType.CI;
        if (inCi == targetsCi) {
            allRunFor(spec, ops);
        }
        return false;
    }

    @Override
    public String toString() {
        return "EnvFilterOp{allowed=" + allowedEnvType + "}";
    }
}
