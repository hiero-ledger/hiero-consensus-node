// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.UNIQUE_PREFIXES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.regression.factories.RegressionProviderFactory.factoryFrom;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.spec.infrastructure.providers.ops.BiasedDelegatingProvider;
import com.hedera.services.bdd.spec.keys.TrieSigMapGenerator;
import com.hedera.services.bdd.suites.regression.UmbrellaRedux;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Smoke test that runs random mixed operations with UNIQUE_PREFIXES sig maps,
 * verifying broad transaction-type coverage with short prefix signing.
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class UniquePrefixesSmokeTest {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @HapiTest
    @DisplayName("All major transaction types succeed with UNIQUE_PREFIXES")
    final Stream<DynamicTest> allTransactionTypesWithUniquePrefixes() {
        return hapiTest(runWithProvider(uniquePrefixesFactory())
                .lasting(10L, TimeUnit.SECONDS)
                .maxOpsPerSec(50)
                .loggingOff());
    }

    private static Function<HapiSpec, OpProvider> uniquePrefixesFactory() {
        final var base = factoryFrom(() -> UmbrellaRedux.DEFAULT_PROPERTIES);
        return spec -> {
            final var provider = (BiasedDelegatingProvider) base.apply(spec);
            return provider.withSigMapGen(TrieSigMapGenerator.withNature(UNIQUE_PREFIXES));
        };
    }
}
