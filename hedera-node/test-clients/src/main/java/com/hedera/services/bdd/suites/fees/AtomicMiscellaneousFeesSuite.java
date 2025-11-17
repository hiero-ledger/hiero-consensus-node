// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of MiscellaneousFeesSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm the fees are the same
@Tag(MATS)
class AtomicMiscellaneousFeesSuite {

    private static final String PRNG_IS_ENABLED = "utilPrng.isEnabled";
    private static final String BOB = "bob";
    private static final double BASE_FEE_MISC_PRNG_TRX = 0.001;
    private static final double EXPECTED_FEE_PRNG_RANGE_TRX = 0.0010010316;
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String ATOMIC_BATCH = "atomicBatch";

    @HapiTest
    @DisplayName("USD base fee as expected for Prng transaction")
    final Stream<DynamicTest> miscPrngTrxBaseUSDFee() {
        final var baseTxn = "prng";
        final var plusRangeTxn = "prngWithRange";

        return hapiTest(
                overridingAllOf(Map.of(PRNG_IS_ENABLED, "true")),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS),
                cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                hapiPrng().payingWith(BOB).via(baseTxn).blankMemo().logged(),
                getTxnRecord(baseTxn).hasOnlyPseudoRandomBytes().logged(),
                validateChargedUsd(baseTxn, BASE_FEE_MISC_PRNG_TRX),
                atomicBatch(hapiPrng(10)
                                .payingWith(BOB)
                                .via(plusRangeTxn)
                                .blankMemo()
                                .logged()
                                .batchKey(BATCH_OPERATOR))
                        .via(ATOMIC_BATCH)
                        .signedByPayerAnd(BATCH_OPERATOR)
                        .payingWith(BATCH_OPERATOR),
                validateInnerTxnChargedUsd(plusRangeTxn, ATOMIC_BATCH, EXPECTED_FEE_PRNG_RANGE_TRX, 5));
    }
}
