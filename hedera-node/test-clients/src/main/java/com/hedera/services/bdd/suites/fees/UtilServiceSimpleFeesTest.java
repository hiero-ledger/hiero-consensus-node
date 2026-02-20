// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.nodeFeeFromBytesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;

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
    private static final int NODE_INCLUDED_BYTES = 1024;
    private static final int NETWORK_MULTIPLIER = 9;

    @HapiTest
    @DisplayName("USD base fee as expected for PRNG transaction")
    final Stream<DynamicTest> prngBaseUSDFee() {
        return hapiTest(
                overridingAllOf(Map.of(PRNG_IS_ENABLED, "true")),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                hapiPrng()
                        .payingWith(CIVILIAN)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("prngTxn")
                        .blankMemo(),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "prngTxn");
                    final var expectedFee = BASE_FEE_PRNG + nodeFeeFromBytesUsd(signedTxnSize);
                    allRunFor(spec, validateChargedUsd("prngTxn", expectedFee));
                }));
    }

    @HapiTest
    @DisplayName("USD base fee as expected for AtomicBatch transaction")
    final Stream<DynamicTest> atomicBatchBaseUSDFee() {
        final var batchOperator = "batchOperator";
        final var innerTxn = cryptoCreate("innerAccount").balance(ONE_HBAR).batchKey(batchOperator);

        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                atomicBatch(innerTxn)
                        .payingWith(batchOperator)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("batchTxn"),
                withOpContext((spec, log) -> {
                    final var signedTxnSize = signedTxnSizeFor(spec, "batchTxn");
                    final var expectedFee = BASE_FEE_ATOMIC_BATCH + nodeFeeFromBytesUsd(signedTxnSize);
                    allRunFor(spec, validateChargedUsd("batchTxn", expectedFee));
                }));
    }
}
