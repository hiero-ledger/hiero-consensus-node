// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.util;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.hapiPrng;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.submitModified;
import static com.hedera.services.bdd.spec.utilops.mod.ModificationUtils.withSuccessivelyVariedBodyIds;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PRNG_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class UtilPrngSuite {
    public static final String BOB = "bob";

    @HapiTest
    final Stream<DynamicTest> failsInPreCheckForNegativeRange() {
        return defaultHapiSpec("failsInPreCheckForNegativeRange")
                .given(
                        cryptoCreate(BOB).balance(ONE_HUNDRED_HBARS),
                        hapiPrng(-10)
                                .payingWith(BOB)
                                .blankMemo()
                                .hasPrecheck(INVALID_PRNG_RANGE)
                                .logged(),
                        hapiPrng(0).payingWith(BOB).blankMemo().hasPrecheck(OK).logged())
                .when()
                .then();
    }

    @HapiTest
    final Stream<DynamicTest> idVariantsTreatedAsExpected() {
        return defaultHapiSpec("idVariantsTreatedAsExpected")
                .given()
                .when()
                .then(submitModified(withSuccessivelyVariedBodyIds(), () -> hapiPrng(123)));
    }

    @HapiTest
    @Tag(MATS)
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
                        hapiPrng().payingWith(BOB).blankMemo().via("prng").logged(),
                        // n-1, n-2 running hash and running has set
                        getTxnRecord("prng").logged(),
                        // n-1, n-2, n-3 running hash and running has set
                        hapiPrng(10).payingWith(BOB).via(rangeTxn1).blankMemo().logged(),
                        getTxnRecord(rangeTxn1).logged(),
                        hapiPrng().payingWith(BOB).via("prng2").blankMemo().logged())
                .when(
                        // should have pseudo random data
                        hapiPrng(10).payingWith(BOB).via(rangeTxn).blankMemo().logged(),
                        getTxnRecord(rangeTxn)
                                .hasOnlyPseudoRandomNumberInRange(10)
                                .logged())
                .then(
                        hapiPrng()
                                .payingWith(BOB)
                                .via(prngWithoutRange)
                                .blankMemo()
                                .logged(),
                        getTxnRecord(prngWithoutRange)
                                .hasOnlyPseudoRandomBytes()
                                .logged(),
                        hapiPrng(0)
                                .payingWith(BOB)
                                .via(prngWithZeroRange)
                                .blankMemo()
                                .logged(),
                        getTxnRecord(prngWithZeroRange)
                                .hasOnlyPseudoRandomBytes()
                                .logged(),
                        hapiPrng()
                                .range(Integer.MAX_VALUE)
                                .payingWith(BOB)
                                .via(prngWithMaxRange)
                                .blankMemo()
                                .logged(),
                        getTxnRecord(prngWithMaxRange)
                                .hasOnlyPseudoRandomNumberInRange(Integer.MAX_VALUE)
                                .logged(),
                        hapiPrng()
                                .range(Integer.MIN_VALUE)
                                .blankMemo()
                                .payingWith(BOB)
                                .hasPrecheck(INVALID_PRNG_RANGE));
    }
}
