// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;

import java.util.Map;

@HapiTestLifecycle
public class AtomicBatchEndToEndConsensusAndTokenServiceTests {

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("atomicBatch.isEnabled", "true", "atomicBatch.maxNumberOfTransactions", "50"));
    }
}
