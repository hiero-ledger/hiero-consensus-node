// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.regression.factories.AtomicBatchProviderFactory.atomicBatchFuzzingWith;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.initOperations;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(NOT_REPEATABLE)
@HapiTestLifecycle
public class AtomicBatchFuzzing {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(
                Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }

    private static final String PROPERTIES = "id-fuzzing.properties";
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(10);
    private final AtomicInteger maxPendingOps = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger backoffSleepSecs = new AtomicInteger(Integer.MAX_VALUE);

    // Note that when running this test there will be an error log indicating that there is a missing
    // AccountID(or some other entity) from the registry. This is due to the nature of the atomic batch.
    // The way the fuzz testing framework works is that it will generate a lot of random entities and sometimes
    // an entity can be deleted from a previous operation, but since all operations are happening in a batch, the
    // registry is not updated. This is not a problem because we are executed the needed batch operations anyway.
    // To fix the issue we'll need to rework the framework and I think it's not worth it at this point.
    @HapiTest
    final Stream<DynamicTest> atomicMixedOperations() {
        return hapiTest(flattened(
                initOperations(),
                runWithProvider(atomicBatchFuzzingWith(PROPERTIES))
                        .lasting(10L, TimeUnit.SECONDS)
                        .maxOpsPerSec(maxOpsPerSec::get)
                        .maxPendingOps(maxPendingOps::get)
                        .backoffSleepSecs(backoffSleepSecs::get)));
    }
}
