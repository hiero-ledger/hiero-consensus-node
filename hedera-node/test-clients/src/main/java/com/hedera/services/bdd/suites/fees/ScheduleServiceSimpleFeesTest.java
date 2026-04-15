// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SERIAL;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.SIGNATURE_FEE_AFTER_MULTIPLIER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.OTHER_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTest;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class ScheduleServiceSimpleFeesTest {
    private static final double BASE_FEE_SCHEDULE_CREATE = 0.01;
    private static final double BASE_FEE_SCHEDULE_SIGN = 0.001;
    private static final double BASE_FEE_SCHEDULE_DELETE = 0.001;
    private static final double BASE_FEE_SCHEDULE_INFO = 0.0001;
    private static final double BASE_FEE_CONTRACT_CALL = 0.1;
    private static final long EXPECTED_NODE_PAYMENT_TINYCENTS = 84L;

    @HapiTest
    @DisplayName("Schedule ops have expected USD fees")
    final Stream<DynamicTest> scheduleOpsBaseUSDFees() {
        final String SCHEDULE_NAME = "canonical";
        return hapiTest(
                uploadInitCode(SIMPLE_UPDATE),
                cryptoCreate(OTHER_PAYER),
                cryptoCreate(PAYING_SENDER),
                cryptoCreate(RECEIVER).receiverSigRequired(true),
                contractCreate(SIMPLE_UPDATE).gas(300_000L),
                scheduleCreate(
                                SCHEDULE_NAME,
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .blankMemo()
                                        .fee(ONE_HBAR))
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("canonicalCreation")
                        .fee(ONE_HBAR),
                scheduleSign(SCHEDULE_NAME)
                        .fee(ONE_HBAR)
                        .via("canonicalSigning")
                        .payingWith(PAYING_SENDER)
                        .signedBy(PAYING_SENDER),
                scheduleSign(SCHEDULE_NAME)
                        .fee(ONE_HBAR)
                        .via("multiScheduleSign")
                        .payingWith(PAYING_SENDER)
                        .signedBy(RECEIVER, PAYING_SENDER),
                scheduleCreate(
                                "tbd",
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .fee(ONE_HBAR))
                        .fee(ONE_HBAR)
                        .payingWith(PAYING_SENDER)
                        .adminKey(PAYING_SENDER),
                scheduleDelete("tbd")
                        .via("canonicalDeletion")
                        .payingWith(PAYING_SENDER)
                        .signedBy(PAYING_SENDER)
                        .fee(ONE_HBAR),
                scheduleCreate(
                                "contractCall",
                                contractCall(SIMPLE_UPDATE, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(24_000)
                                        .fee(ONE_HBAR))
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .fee(ONE_HBAR)
                        .via("canonicalContractCall"),
                getScheduleInfo(SCHEDULE_NAME)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("getScheduleInfoBasic"),
                validateChargedUsd("canonicalCreation", BASE_FEE_SCHEDULE_CREATE),
                validateChargedUsd("canonicalSigning", BASE_FEE_SCHEDULE_SIGN),
                // validate the fee when we have single overage signature
                validateChargedUsd("multiScheduleSign", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER),
                validateChargedUsd("canonicalDeletion", BASE_FEE_SCHEDULE_DELETE),
                validateChargedUsd("canonicalContractCall", BASE_FEE_CONTRACT_CALL),
                validateChargedUsd("getScheduleInfoBasic", BASE_FEE_SCHEDULE_INFO),
                validateNodePaymentAmountForQuery("getScheduleInfoBasic", EXPECTED_NODE_PAYMENT_TINYCENTS));
    }

    @HapiTest
    @DisplayName("schedule get info - invalid schedule fails - no fee charged")
    final Stream<DynamicTest> scheduleGetInfoInvalidScheduleFails() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();

        return hapiTest(
                cryptoCreate(PAYING_SENDER).balance(ONE_HUNDRED_HBARS),
                getAccountBalance(PAYING_SENDER).exposingBalanceTo(initialBalance::set),
                getScheduleInfo("0.0.99999999").payingWith(PAYING_SENDER).hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                getAccountBalance(PAYING_SENDER).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }));
    }

    @HapiTest
    @DisplayName("Scheduled CryptoTransfer full lifecycle - create, sign, execute fees")
    final Stream<DynamicTest> scheduledCryptoTransferFullLifecycleFees() {
        return hapiTest(
                cryptoCreate(PAYING_SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                scheduleCreate(
                                "xferSchedule",
                                cryptoTransfer(tinyBarsFromTo(PAYING_SENDER, RECEIVER, 1L))
                                        .blankMemo()
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYING_SENDER)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                // Sign with PAYING_SENDER to provide the required sigs — this triggers execution
                scheduleSign("xferSchedule")
                        .alsoSigningWith(PAYING_SENDER)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER, PAYING_SENDER)
                        .via("signTxn")
                        .fee(ONE_HBAR),
                // Verify fees at each stage
                validateChargedUsd("createTxn", BASE_FEE_SCHEDULE_CREATE),
                validateChargedUsd("signTxn", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER),
                // Verify execution happened — receiver got the HBAR
                getAccountBalance(RECEIVER).hasTinyBars(1L),
                // Verify execution fee on inner transaction
                withOpContext((spec, log) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    // The inner CryptoTransfer execution should succeed
                    var record = triggeredTx.getResponseRecord();
                    assertEquals(SUCCESS, record.getReceipt().getStatus());
                }));
    }

    @HapiTest
    @DisplayName("Scheduled CryptoTransfer triggers auto-creation on execution")
    final Stream<DynamicTest> scheduledCryptoTransferTriggersAutoCreation() {
        final var alias = "ed25519Alias";
        return hapiTest(
                cryptoCreate(PAYING_SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(alias).shape(ED25519),
                // Schedule a transfer to an alias — auto-creation will happen at execution time
                scheduleCreate(
                                "autoCreateSchedule",
                                cryptoTransfer(movingHbar(ONE_HBAR).between(PAYING_SENDER, alias))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYING_SENDER)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                scheduleSign("autoCreateSchedule")
                        .alsoSigningWith(PAYING_SENDER)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER, PAYING_SENDER)
                        .via("signTxn")
                        .fee(ONE_HBAR),
                // Verify schedule create and sign fees
                validateChargedUsd("createTxn", BASE_FEE_SCHEDULE_CREATE),
                validateChargedUsd("signTxn", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER),
                // Verify auto-created account exists and has the HBAR
                getAliasedAccountInfo(alias)
                        .has(accountWith().key(alias).alias(alias).maxAutoAssociations(-1)),
                // Verify inner execution succeeded
                withOpContext((spec, log) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus());
                }));
    }

    @HapiTest
    @DisplayName("Scheduled CryptoTransfer triggers auto-association on execution")
    final Stream<DynamicTest> scheduledCryptoTransferTriggersAutoAssociation() {
        final var token = "fungibleToken";
        final var unassociatedReceiver = "unassociatedReceiver";
        return hapiTest(
                cryptoCreate(PAYING_SENDER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(unassociatedReceiver)
                        .maxAutomaticTokenAssociations(-1)
                        .balance(ONE_HBAR),
                tokenCreate(token)
                        .tokenType(FUNGIBLE_COMMON)
                        .initialSupply(1000L)
                        .treasury(PAYING_SENDER),
                // Schedule a FT transfer to unassociated receiver — auto-association at execution
                scheduleCreate(
                                "autoAssocSchedule",
                                cryptoTransfer(moving(10L, token).between(PAYING_SENDER, unassociatedReceiver))
                                        .fee(ONE_HBAR))
                        .designatingPayer(PAYING_SENDER)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                scheduleSign("autoAssocSchedule")
                        .alsoSigningWith(PAYING_SENDER)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER, PAYING_SENDER)
                        .via("signTxn")
                        .fee(ONE_HBAR),
                // Verify schedule ops fees
                validateChargedUsd("createTxn", BASE_FEE_SCHEDULE_CREATE),
                validateChargedUsd("signTxn", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER),
                // Verify auto-association happened — receiver has the token
                getAccountInfo(unassociatedReceiver).hasToken(relationshipWith(token)),
                getAccountBalance(unassociatedReceiver).hasTokenBalance(token, 10L),
                // Verify execution succeeded
                withOpContext((spec, log) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus());
                }));
    }

    @HapiTest
    @DisplayName("Scheduled TokenMint full lifecycle - create, sign, execute fees")
    final Stream<DynamicTest> scheduledTokenMintFullLifecycleFees() {
        final var token = "mintableToken";
        final var supplyKey = "supplyKey";
        final var treasury = "treasury";
        final var schedulePayer = "schedulePayer";
        return hapiTest(
                cryptoCreate(treasury).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(schedulePayer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(supplyKey),
                tokenCreate(token).supplyKey(supplyKey).treasury(treasury).initialSupply(100L),
                // Schedule a mint of 50 tokens
                scheduleCreate("mintSchedule", mintToken(token, 50))
                        .designatingPayer(schedulePayer)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                // Sign with supply key, treasury, and schedule payer to trigger execution
                scheduleSign("mintSchedule")
                        .alsoSigningWith(supplyKey, schedulePayer, treasury)
                        .via("signTxn")
                        .hasKnownStatus(SUCCESS),
                // Verify fees
                validateChargedUsd("createTxn", BASE_FEE_SCHEDULE_CREATE),
                // Verify mint happened
                getTokenInfo(token).hasTotalSupply(150L),
                // Verify execution record
                withOpContext((spec, log) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus());
                }));
    }

    @HapiTest
    @DisplayName("Scheduled TokenBurn full lifecycle - create, sign, execute fees")
    final Stream<DynamicTest> scheduledTokenBurnFullLifecycleFees() {
        final var token = "burnableToken";
        final var supplyKey = "burnSupplyKey";
        final var treasury = "burnTreasury";
        final var schedulePayer = "burnSchedulePayer";
        return hapiTest(
                cryptoCreate(treasury).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(schedulePayer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(supplyKey),
                tokenCreate(token).supplyKey(supplyKey).treasury(treasury).initialSupply(100L),
                // Schedule a burn of 30 tokens
                scheduleCreate("burnSchedule", burnToken(token, 30))
                        .designatingPayer(schedulePayer)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                // Sign with supply key, treasury, and payer to trigger execution
                scheduleSign("burnSchedule")
                        .alsoSigningWith(supplyKey, schedulePayer, treasury)
                        .via("signTxn")
                        .hasKnownStatus(SUCCESS),
                // Verify fees
                validateChargedUsd("createTxn", BASE_FEE_SCHEDULE_CREATE),
                // Verify burn happened
                getTokenInfo(token).hasTotalSupply(70L),
                // Verify execution record
                withOpContext((spec, log) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus());
                }));
    }

    @HapiTest
    @Tag(SERIAL)
    @DisplayName("Scheduled ContractCall full lifecycle - create, sign, execute fees")
    @Disabled("Waiting on BaseTranslator sidecar fix to merge to main")
    final Stream<DynamicTest> scheduledContractCallFullLifecycleFees() {
        final var schedulePayer = "contractSchedulePayer";
        return hapiTest(
                uploadInitCode(SIMPLE_UPDATE),
                cryptoCreate(schedulePayer).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OTHER_PAYER).balance(ONE_HUNDRED_HBARS),
                contractCreate(SIMPLE_UPDATE).gas(300_000L),
                // Schedule a contract call
                scheduleCreate(
                                "contractCallSchedule",
                                contractCall(SIMPLE_UPDATE, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(100_000)
                                        .fee(ONE_HBAR))
                        .designatingPayer(schedulePayer)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER)
                        .via("createTxn")
                        .fee(ONE_HBAR),
                // Sign with schedule payer to trigger execution
                scheduleSign("contractCallSchedule")
                        .alsoSigningWith(schedulePayer)
                        .payingWith(OTHER_PAYER)
                        .signedBy(OTHER_PAYER, schedulePayer)
                        .via("signTxn")
                        .fee(ONE_HBAR),
                // Verify sign fee (schedule create with contract call charges near-zero — known issue)
                validateChargedUsd("signTxn", BASE_FEE_SCHEDULE_SIGN + SIGNATURE_FEE_AFTER_MULTIPLIER),
                // Verify execution succeeded
                withOpContext((spec, log) -> {
                    var triggeredTx = getTxnRecord("createTxn").scheduled();
                    allRunFor(spec, triggeredTx);
                    assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus());
                }));
    }
}
