// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.util;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.TOKEN;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PRNG_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

// This test cases are direct copies of UtilPrngSuite. The difference here is that
// we are wrapping the operations in an atomic batch to confirm that everything works as expected.
@HapiTestLifecycle
@Tag(TOKEN)
@Tag(MATS)
class AtomicUtilPrngSuite {

    public static final String BOB = "bob";
    private static final String BATCH_OPERATOR = "batchOperator";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.doAdhoc(cryptoCreate(BATCH_OPERATOR).balance(ONE_MILLION_HBARS));
    }

    @HapiTest
    final Stream<DynamicTest> failsInPreCheckForNegativeRange() {
        return defaultHapiSpec("failsInPreCheckForNegativeRange")
                .given(
                        cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                        atomicBatch(hapiPrng(-10)
                                        .payingWith(BOB)
                                        .blankMemo()
                                        .hasPrecheck(INVALID_PRNG_RANGE)
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INVALID_PRNG_RANGE),
                        atomicBatch(hapiPrng(0)
                                        .payingWith(BOB)
                                        .blankMemo()
                                        .hasPrecheck(OK)
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                .when()
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> happyPathWorksForRangeAndBitString() {
        final var rangeTxn = "prngWithRange";
        final var rangeTxn1 = "prngWithRange1";
        final var prngWithoutRange = "prngWithoutRange";
        final var prngWithMaxRange = "prngWithMaxRange";
        final var prngWithZeroRange = "prngWithZeroRange";
        return defaultHapiSpec("happyPathWorksForRangeAndBitString")
                .given(
                        // running hash is set
                        cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                        // n-1 running hash and running has set
                        atomicBatch(hapiPrng()
                                        .payingWith(BOB)
                                        .blankMemo()
                                        .via("prng")
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        // n-1, n-2 running hash and running has set
                        getTxnRecord("prng").logged(),
                        // n-1, n-2, n-3 running hash and running has set
                        atomicBatch(hapiPrng(10)
                                        .payingWith(BOB)
                                        .via(rangeTxn1)
                                        .blankMemo()
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTxnRecord(rangeTxn1).logged(),
                        atomicBatch(hapiPrng()
                                        .payingWith(BOB)
                                        .via("prng2")
                                        .blankMemo()
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR))
                .when(
                        // should have pseudo random data
                        atomicBatch(hapiPrng(10)
                                        .payingWith(BOB)
                                        .via(rangeTxn)
                                        .blankMemo()
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTxnRecord(rangeTxn)
                                .hasOnlyPseudoRandomNumberInRange(10)
                                .logged())
                .then(
                        atomicBatch(hapiPrng()
                                        .payingWith(BOB)
                                        .via(prngWithoutRange)
                                        .blankMemo()
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTxnRecord(prngWithoutRange)
                                .hasOnlyPseudoRandomBytes()
                                .logged(),
                        atomicBatch(hapiPrng(0)
                                        .payingWith(BOB)
                                        .via(prngWithZeroRange)
                                        .blankMemo()
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTxnRecord(prngWithZeroRange)
                                .hasOnlyPseudoRandomBytes()
                                .logged(),
                        atomicBatch(hapiPrng()
                                        .range(Integer.MAX_VALUE)
                                        .payingWith(BOB)
                                        .via(prngWithMaxRange)
                                        .blankMemo()
                                        .logged()
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR),
                        getTxnRecord(prngWithMaxRange)
                                .hasOnlyPseudoRandomNumberInRange(Integer.MAX_VALUE)
                                .logged(),
                        atomicBatch(hapiPrng()
                                        .range(Integer.MIN_VALUE)
                                        .blankMemo()
                                        .payingWith(BOB)
                                        .hasPrecheck(INVALID_PRNG_RANGE)
                                        .batchKey(BATCH_OPERATOR))
                                .payingWith(BATCH_OPERATOR)
                                .hasPrecheck(INVALID_PRNG_RANGE));
    }
}
