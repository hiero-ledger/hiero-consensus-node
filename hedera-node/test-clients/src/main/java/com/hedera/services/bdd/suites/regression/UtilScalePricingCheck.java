// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// Run this suite first in CI, since it assumes there are no NFTs in state at the beginning of the test
@Order(Integer.MIN_VALUE)
@Tag(MATS)
public class UtilScalePricingCheck {
    private static final String NON_FUNGIBLE_TOKEN = "NON_FUNGIBLE_TOKEN";

    @LeakyHapiTest(overrides = {"tokens.nfts.maxAllowedMints", "fees.percentUtilizationScaleFactors"})
    final Stream<DynamicTest> nftPriceScalesWithUtilization() {
        final var civilian = "civilian";
        final var maxAllowed = 10;
        final IntFunction<String> mintOp = i -> "mint" + i;
        final var standard100ByteMetadata = ByteString.copyFromUtf8(
                "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789");
        final AtomicLong baseFee = new AtomicLong();
        return hapiTest(
                overridingTwo(
                        "tokens.nfts.maxAllowedMints",
                        "" + maxAllowed,
                        "fees.percentUtilizationScaleFactors",
                        "NFT(0,1:1,50,5:1,90,50:1)"),
                cryptoCreate(civilian).balance(ONE_MILLION_HBARS),
                tokenCreate(NON_FUNGIBLE_TOKEN)
                        .supplyKey(civilian)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                blockingOrder(IntStream.range(1, maxAllowed + 1)
                        .mapToObj(i -> mintToken(NON_FUNGIBLE_TOKEN, List.of(standard100ByteMetadata))
                                .payingWith(civilian)
                                .blankMemo()
                                .fee(1000 * ONE_HUNDRED_HBARS)
                                .via(mintOp.apply(i)))
                        .toArray(HapiSpecOperation[]::new)),
                blockingOrder(IntStream.range(1, maxAllowed + 1)
                        .mapToObj(i -> getTxnRecord(mintOp.apply(i))
                                .noLogging()
                                .loggingOnlyFee()
                                .exposingTo(mintRecord -> {
                                    if (i == 1) {
                                        baseFee.set(mintRecord.getTransactionFee());
                                    } else {
                                        final var multiplier = expectedMultiplier(i);
                                        final var expected = multiplier * baseFee.get();
                                        assertEquals(
                                                expected,
                                                mintRecord.getTransactionFee(),
                                                multiplier + "x multiplier should be in effect at " + i + " mints");
                                    }
                                }))
                        .toArray(HapiSpecOperation[]::new)));
    }

    private long expectedMultiplier(final int mintNo) {
        if (mintNo <= 5) {
            return 1;
        } else if (mintNo <= 9) {
            return 5;
        } else {
            return 50;
        }
    }
}
