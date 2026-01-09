// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Tests for simple fee calculations in the Util service (PRNG and AtomicBatch).
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
public class UtilServiceSimpleFeesTest {
    private static final String CIVILIAN = "civilian";
    private static final String PRNG_IS_ENABLED = "utilPrng.isEnabled";

    // Base fees from the simple fee schedule
    private static final double BASE_FEE_PRNG = 0.001;
    private static final double BASE_FEE_ATOMIC_BATCH = 0.001;

    @HapiTest
    @DisplayName("USD base fee as expected for PRNG transaction")
    final Stream<DynamicTest> prngBaseUSDFee() {
        return hapiTest(
                overridingAllOf(Map.of(PRNG_IS_ENABLED, "true")),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                hapiPrng().payingWith(CIVILIAN).via("prngTxn").blankMemo(),
                validateChargedUsd("prngTxn", BASE_FEE_PRNG));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for AtomicBatch transaction")
    final Stream<DynamicTest> atomicBatchBaseUSDFee() {
        final var batchOperator = "batchOperator";
        final var innerTxn = cryptoCreate("innerAccount").balance(ONE_HBAR).batchKey(batchOperator);

        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                atomicBatch(innerTxn).payingWith(batchOperator).via("batchTxn"),
                validateChargedUsd("batchTxn", BASE_FEE_ATOMIC_BATCH));
    }
}
