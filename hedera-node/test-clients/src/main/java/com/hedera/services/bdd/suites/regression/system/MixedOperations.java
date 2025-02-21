// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.factoryFrom;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.suites.regression.UmbrellaRedux;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Returns mixed operations that can be used for restart and reconnect tests.
 * These operations will be further extended in the future
 */
public class MixedOperations {
    final int numSubmissions;

    public MixedOperations(int numSubmissions) {
        this.numSubmissions = numSubmissions;
    }

    public static HapiSpecOperation burstOfTps(final int tps, @NonNull final Duration duration) {
        return runWithProvider(factoryFrom(() -> UmbrellaRedux.DEFAULT_PROPERTIES))
                .lasting(duration.toMillis(), TimeUnit.MILLISECONDS)
                .maxOpsPerSec(tps)
                .loggingOff();
    }
}
