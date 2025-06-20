// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.NOT_REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT;
import static com.hedera.services.bdd.spec.infrastructure.OpProvider.UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.idFuzzingWith;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.idTransferToRandomKeyWith;
import static com.hedera.services.bdd.suites.regression.factories.IdFuzzingProviderFactory.initOperations;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * We want to make this suite exercise all forms of identity a Hedera account may have, under all
 * possible circumstances. (This could take us a while to do.)
 *
 * <p>See <a href="https://github.com/hashgraph/hedera-services/issues/4565">#4565</a> for details.
 */
@Tag(NOT_REPEATABLE)
public class AddressAliasIdFuzzing {
    private static final String PROPERTIES = "id-fuzzing.properties";
    private final AtomicInteger maxOpsPerSec = new AtomicInteger(10);
    private final AtomicInteger maxPendingOps = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger backoffSleepSecs = new AtomicInteger(Integer.MAX_VALUE);

    @HapiTest
    final Stream<DynamicTest> addressAliasIdFuzzing() {
        return hapiTest(flattened(
                initOperations(),
                runWithProvider(idFuzzingWith(PROPERTIES))
                        .lasting(10L, TimeUnit.SECONDS)
                        .maxOpsPerSec(maxOpsPerSec::get)
                        .maxPendingOps(maxPendingOps::get)
                        .backoffSleepSecs(backoffSleepSecs::get)));
    }

    @HapiTest
    final Stream<DynamicTest> transferToKeyFuzzing() {
        return hapiTest(
                cryptoCreate(UNIQUE_PAYER_ACCOUNT)
                        .balance(UNIQUE_PAYER_ACCOUNT_INITIAL_BALANCE)
                        .withRecharging(),
                runWithProvider(idTransferToRandomKeyWith(PROPERTIES)).lasting(10L, TimeUnit.SECONDS));
    }
}
