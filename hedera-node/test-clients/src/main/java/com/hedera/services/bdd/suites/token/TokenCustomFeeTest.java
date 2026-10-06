// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.token;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.AssessedCustomFee;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
@Execution(ExecutionMode.SAME_THREAD)
@HapiTestLifecycle
public class TokenCustomFeeTest {
    private static final String FRACTIONAL_TOKEN = "fractionalEffectivePayerToken";
    private static final String FRACTIONAL_TREASURY = "fractionalTreasury";
    private static final String FRACTIONAL_SENDER = "fractionalSender";
    private static final String FRACTIONAL_SMALL_RECEIVER = "fractionalSmallReceiver";
    private static final String FRACTIONAL_LARGE_RECEIVER = "fractionalLargeReceiver";
    private static final String FRACTIONAL_FIRST_COLLECTOR = "fractionalFirstCollector";
    private static final String FRACTIONAL_SECOND_COLLECTOR = "fractionalSecondCollector";
    private static final String FRACTIONAL_TRIGGER_TX = "fractionalTriggerTx";
    private static final String TOKEN = "twoFractionalCollectorsRecordDriftToken";
    private static final String TREASURY = "twoFractionalCollectorsRecordDriftTreasury";
    private static final String SENDER = "twoFractionalCollectorsRecordDriftSender";
    private static final String RECEIVER = "twoFractionalCollectorsRecordDriftReceiver";
    private static final String FIRST_COLLECTOR = "twoFractionalCollectorsRecordDriftFirstCollector";
    private static final String SECOND_COLLECTOR = "twoFractionalCollectorsRecordDriftSecondCollector";
    private static final String TRIGGER_TX = "twoFractionalCollectorsRecordDriftTransfer";

    @HapiTest
    Stream<DynamicTest> nativeTransferRecordOmitsFractionalCollectorThatPaidLaterFee() {
        return hapiTest(
                cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FIRST_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(SECOND_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                tokenCreate(TOKEN)
                        .treasury(TREASURY)
                        .initialSupply(100L)
                        .withCustom(fractionalFee(1L, 10L, 0L, OptionalLong.empty(), FIRST_COLLECTOR))
                        .withCustom(fractionalFee(1L, 5L, 0L, OptionalLong.empty(), SECOND_COLLECTOR))
                        .signedBy(DEFAULT_PAYER, TREASURY, FIRST_COLLECTOR, SECOND_COLLECTOR),
                tokenAssociate(SENDER, TOKEN),
                tokenAssociate(RECEIVER, TOKEN),
                cryptoTransfer(moving(100L, TOKEN).between(TREASURY, SENDER))
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                cryptoTransfer(moving(50L, TOKEN).between(SENDER, RECEIVER))
                        .payingWith(DEFAULT_PAYER)
                        .signedBy(DEFAULT_PAYER, SENDER, RECEIVER)
                        .via(TRIGGER_TX)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(RECEIVER).hasTokenBalance(TOKEN, 36L),
                getAccountBalance(FIRST_COLLECTOR).hasTokenBalance(TOKEN, 4L),
                getAccountBalance(SECOND_COLLECTOR).hasTokenBalance(TOKEN, 10L),
                withOpContext((spec, opLog) -> {
                    final var recordOp = getTxnRecord(TRIGGER_TX).assertingNothingAboutHashes();
                    allRunFor(spec, recordOp);

                    final var receiverId = spec.registry().getAccountID(RECEIVER);
                    final var firstCollectorId = spec.registry().getAccountID(FIRST_COLLECTOR);
                    final var secondCollectorId = spec.registry().getAccountID(SECOND_COLLECTOR);
                    final var fees = recordOp.getResponseRecord().getAssessedCustomFeesList();
                    final var firstFee = feeForCollector(fees, firstCollectorId);
                    final var secondFee = feeForCollector(fees, secondCollectorId);

                    assertEquals(
                            SUCCESS, recordOp.getResponseRecord().getReceipt().getStatus());
                    assertEquals(5L, firstFee.getAmount(), "First fractional fee amount changed");
                    assertEquals(10L, secondFee.getAmount(), "Second fractional fee amount changed");
                    assertEquals(
                            List.of(receiverId),
                            firstFee.getEffectivePayerAccountIdList(),
                            "First fee should be paid only by the receiver");
                    assertEquals(
                            List.of(receiverId, firstCollectorId),
                            secondFee.getEffectivePayerAccountIdList(),
                            "Second fee record omitted the first collector, which paid 1 token of this fee");
                }));
    }

    @HapiTest
    Stream<DynamicTest> fractionalFeeRecordNamesEffectivePayerThatDidNotPayLaterFee() {
        return hapiTest(
                cryptoCreate(FRACTIONAL_TREASURY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FRACTIONAL_SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FRACTIONAL_SMALL_RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FRACTIONAL_LARGE_RECEIVER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FRACTIONAL_FIRST_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(FRACTIONAL_SECOND_COLLECTOR).balance(ONE_HUNDRED_HBARS),
                tokenCreate(FRACTIONAL_TOKEN)
                        .treasury(FRACTIONAL_TREASURY)
                        .initialSupply(1_000L)
                        .withCustom(fractionalFee(1L, 100L, 0L, OptionalLong.empty(), FRACTIONAL_FIRST_COLLECTOR))
                        .withCustom(fractionalFee(1L, 100L, 0L, OptionalLong.empty(), FRACTIONAL_SECOND_COLLECTOR))
                        .signedBy(
                                DEFAULT_PAYER,
                                FRACTIONAL_TREASURY,
                                FRACTIONAL_FIRST_COLLECTOR,
                                FRACTIONAL_SECOND_COLLECTOR),
                tokenAssociate(FRACTIONAL_SENDER, FRACTIONAL_TOKEN),
                tokenAssociate(FRACTIONAL_SMALL_RECEIVER, FRACTIONAL_TOKEN),
                tokenAssociate(FRACTIONAL_LARGE_RECEIVER, FRACTIONAL_TOKEN),
                cryptoTransfer(moving(100L, FRACTIONAL_TOKEN).between(FRACTIONAL_TREASURY, FRACTIONAL_SENDER)),
                cryptoTransfer(
                                moving(1L, FRACTIONAL_TOKEN).between(FRACTIONAL_SENDER, FRACTIONAL_SMALL_RECEIVER),
                                moving(99L, FRACTIONAL_TOKEN).between(FRACTIONAL_SENDER, FRACTIONAL_LARGE_RECEIVER))
                        .payingWith(FRACTIONAL_SENDER)
                        .fee(ONE_HUNDRED_HBARS)
                        .via(FRACTIONAL_TRIGGER_TX)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(FRACTIONAL_SMALL_RECEIVER).hasTokenBalance(FRACTIONAL_TOKEN, 0L),
                getAccountBalance(FRACTIONAL_LARGE_RECEIVER).hasTokenBalance(FRACTIONAL_TOKEN, 98L),
                getAccountBalance(FRACTIONAL_FIRST_COLLECTOR).hasTokenBalance(FRACTIONAL_TOKEN, 1L),
                getAccountBalance(FRACTIONAL_SECOND_COLLECTOR).hasTokenBalance(FRACTIONAL_TOKEN, 1L),
                withOpContext((spec, opLog) -> {
                    final var recordOp = getTxnRecord(FRACTIONAL_TRIGGER_TX).assertingNothingAboutHashes();
                    allRunFor(spec, recordOp);

                    final var smallReceiverId = spec.registry().getAccountID(FRACTIONAL_SMALL_RECEIVER);
                    final var largeReceiverId = spec.registry().getAccountID(FRACTIONAL_LARGE_RECEIVER);
                    final var firstCollectorId = spec.registry().getAccountID(FRACTIONAL_FIRST_COLLECTOR);
                    final var secondCollectorId = spec.registry().getAccountID(FRACTIONAL_SECOND_COLLECTOR);
                    final var firstFee =
                            feeForCollector(recordOp.getResponseRecord().getAssessedCustomFeesList(), firstCollectorId);
                    final var secondFee = feeForCollector(
                            recordOp.getResponseRecord().getAssessedCustomFeesList(), secondCollectorId);

                    opLog.info("First fractional assessed fee: {}", firstFee);
                    opLog.info("Second fractional assessed fee: {}", secondFee);
                    assertEquals(
                            SUCCESS, recordOp.getResponseRecord().getReceipt().getStatus());
                    assertEquals(1L, firstFee.getAmount(), "First assessed fractional fee amount changed");
                    assertEquals(1L, secondFee.getAmount(), "Second assessed fractional fee amount changed");
                    assertEquals(
                            List.of(smallReceiverId),
                            firstFee.getEffectivePayerAccountIdList(),
                            "First fee should only name the receiver that actually paid it");
                    assertEquals(
                            List.of(largeReceiverId),
                            secondFee.getEffectivePayerAccountIdList(),
                            "Second fee should only name the receiver that actually paid it");
                }));
    }

    private static AssessedCustomFee feeForCollector(
            final List<AssessedCustomFee> assessedFees, final AccountID collector) {
        return assessedFees.stream()
                .filter(fee -> fee.getFeeCollectorAccountId().equals(collector))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing assessed fee for collector " + collector));
    }
}
