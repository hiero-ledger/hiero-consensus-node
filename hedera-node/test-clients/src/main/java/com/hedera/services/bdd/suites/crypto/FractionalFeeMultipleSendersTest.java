// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Non-net-of-transfers fractional fees on transfers with multiple senders, including senders exempt from the fee.
 */
@Tag(CRYPTO)
public class FractionalFeeMultipleSendersTest {
    private static final long SUPPLY = 100_000_000_000L;
    private static final String TOKEN = "token";
    private static final String TREASURY = "treasury";
    private static final String FRACTIONAL_COLLECTOR = "fractionalCollector";
    // Collects the fixed fee, so it is exempt from the fractional fee
    private static final String FIXED_FEE_COLLECTOR = "fixedFeeCollector";
    private static final String ORDINARY_SENDER = "ordinarySender";
    private static final String RECEIVER = "receiver";
    private static final String HBAR_RECEIVER = "hbarReceiver";
    private static final String FIRST_COLLECTOR = "firstCollector";
    private static final String SECOND_COLLECTOR = "secondCollector";
    private static final String XFER = "xfer";

    @HapiTest
    final Stream<DynamicTest> treasuryAndOrdinarySenders() {
        return hapiTest(flattened(
                tokenWithTenPercentFee(OptionalLong.empty()),
                cryptoTransfer(moving(5_000L, TOKEN).between(TREASURY, ORDINARY_SENDER)),
                cryptoTransfer(
                        moving(5_000L, TOKEN).from(TREASURY),
                        moving(5_000L, TOKEN).from(ORDINARY_SENDER),
                        moving(10_000L, TOKEN).to(RECEIVER)),
                getAccountBalance(RECEIVER).hasTokenBalance(TOKEN, 9_000L),
                getAccountBalance(FRACTIONAL_COLLECTOR).hasTokenBalance(TOKEN, 1_000L)));
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithEqualDebits() {
        return transferFromCollectorAndOrdinarySender(OptionalLong.empty(), 5_000L, 5_000L, 9_000L);
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithUnequalDebits() {
        return transferFromCollectorAndOrdinarySender(OptionalLong.empty(), 9_000L, 1_000L, 9_000L);
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithMaximumFee() {
        return transferFromCollectorAndOrdinarySender(OptionalLong.of(100L), 9_000L, 1_000L, 9_900L);
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithMaximumFeeOfOne() {
        return transferFromCollectorAndOrdinarySender(OptionalLong.of(1L), 1_000L, 1L, 1_000L);
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithHbarTransfer() {
        return hapiTest(flattened(
                tokenWithTenPercentFee(OptionalLong.empty()),
                cryptoCreate(HBAR_RECEIVER).balance(0L),
                fundSenders(9_000L, 1_000L),
                cryptoTransfer(
                                movingHbar(5 * ONE_HBAR).between(RECEIVER, HBAR_RECEIVER),
                                moving(9_000L, TOKEN).from(FIXED_FEE_COLLECTOR),
                                moving(1_000L, TOKEN).from(ORDINARY_SENDER),
                                moving(10_000L, TOKEN).to(RECEIVER))
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, RECEIVER, FIXED_FEE_COLLECTOR, ORDINARY_SENDER),
                getAccountBalance(RECEIVER)
                        .hasTokenBalance(TOKEN, 9_000L)
                        .hasTinyBars(ONE_HUNDRED_HBARS - 5 * ONE_HBAR),
                getAccountBalance(HBAR_RECEIVER).hasTinyBars(5 * ONE_HBAR)));
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithTwoFractionalFees() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(FIRST_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_COLLECTOR),
                cryptoCreate(ORDINARY_SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER),
                tokenCreate(TOKEN)
                        .treasury(TREASURY)
                        .initialSupply(SUPPLY)
                        .withCustom(fractionalFee(35L, 100L, 0L, OptionalLong.empty(), FIRST_COLLECTOR, true))
                        .withCustom(fractionalFee(15L, 100L, 0L, OptionalLong.empty(), SECOND_COLLECTOR, true))
                        .signedBy(DEFAULT_PAYER, TREASURY, FIRST_COLLECTOR, SECOND_COLLECTOR),
                tokenAssociate(ORDINARY_SENDER, TOKEN),
                tokenAssociate(RECEIVER, TOKEN),
                cryptoTransfer(
                        moving(5_000L, TOKEN).between(TREASURY, FIRST_COLLECTOR),
                        moving(5_000L, TOKEN).between(TREASURY, ORDINARY_SENDER)),
                cryptoTransfer(
                        moving(5_000L, TOKEN).from(FIRST_COLLECTOR),
                        moving(5_000L, TOKEN).from(ORDINARY_SENDER),
                        moving(10_000L, TOKEN).to(RECEIVER)),
                getAccountBalance(RECEIVER).hasTokenBalance(TOKEN, 5_000L),
                getAccountBalance(FIRST_COLLECTOR).hasTokenBalance(TOKEN, 3_500L),
                getAccountBalance(SECOND_COLLECTOR).hasTokenBalance(TOKEN, 1_500L));
    }

    @HapiTest
    final Stream<DynamicTest> collectorAndOrdinarySendersWithLargeCollectorDebit() {
        return hapiTest(flattened(
                tokenWithTenPercentFee(OptionalLong.empty()),
                fundSenders(10_000_000_000L, 1L),
                cryptoTransfer(
                                moving(10_000_000_000L, TOKEN).from(FIXED_FEE_COLLECTOR),
                                moving(1L, TOKEN).from(ORDINARY_SENDER),
                                moving(10_000_000_001L, TOKEN).to(RECEIVER))
                        .hasKnownStatus(INSUFFICIENT_SENDER_ACCOUNT_BALANCE_FOR_CUSTOM_FEE),
                getAccountBalance(RECEIVER).hasTokenBalance(TOKEN, 0L)));
    }

    private static Stream<DynamicTest> transferFromCollectorAndOrdinarySender(
            final OptionalLong maxFee,
            final long collectorUnits,
            final long ordinaryUnits,
            final long expectedReceived) {
        final long total = collectorUnits + ordinaryUnits;
        return hapiTest(flattened(
                tokenWithTenPercentFee(maxFee),
                fundSenders(collectorUnits, ordinaryUnits),
                cryptoTransfer(
                                moving(collectorUnits, TOKEN).from(FIXED_FEE_COLLECTOR),
                                moving(ordinaryUnits, TOKEN).from(ORDINARY_SENDER),
                                moving(total, TOKEN).to(RECEIVER))
                        .via(XFER),
                getAccountBalance(RECEIVER).hasTokenBalance(TOKEN, expectedReceived),
                getAccountBalance(FRACTIONAL_COLLECTOR).hasTokenBalance(TOKEN, total - expectedReceived),
                // Assessed for the ordinary sender only: the fractional fee and the fixed hbar fee
                getTxnRecord(XFER)
                        .hasAssessedCustomFeesSize(2)
                        .hasAssessedCustomFee(TOKEN, FRACTIONAL_COLLECTOR, total - expectedReceived)));
    }

    private static SpecOperation[] tokenWithTenPercentFee(final OptionalLong maxFee) {
        return new SpecOperation[] {
            cryptoCreate(TREASURY),
            cryptoCreate(FRACTIONAL_COLLECTOR),
            cryptoCreate(FIXED_FEE_COLLECTOR).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(ORDINARY_SENDER).balance(ONE_HUNDRED_HBARS),
            cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
            tokenCreate(TOKEN)
                    .treasury(TREASURY)
                    .initialSupply(SUPPLY)
                    .withCustom(fixedHbarFee(1L, FIXED_FEE_COLLECTOR))
                    .withCustom(fractionalFee(1L, 10L, 0L, maxFee, FRACTIONAL_COLLECTOR, true))
                    .signedBy(DEFAULT_PAYER, TREASURY, FIXED_FEE_COLLECTOR, FRACTIONAL_COLLECTOR),
            tokenAssociate(FIXED_FEE_COLLECTOR, TOKEN),
            tokenAssociate(ORDINARY_SENDER, TOKEN),
            tokenAssociate(RECEIVER, TOKEN)
        };
    }

    private static SpecOperation fundSenders(final long collectorUnits, final long ordinaryUnits) {
        return cryptoTransfer(
                moving(collectorUnits, TOKEN).between(TREASURY, FIXED_FEE_COLLECTOR),
                moving(ordinaryUnits, TOKEN).between(TREASURY, ORDINARY_SENDER));
    }
}
