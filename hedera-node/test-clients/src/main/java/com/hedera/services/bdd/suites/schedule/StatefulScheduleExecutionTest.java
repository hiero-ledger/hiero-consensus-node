// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.A_TOKEN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.FAILING_TXN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_ACCOUNT;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER_A;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER_B;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER_C;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULE_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TREASURY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.VALID_SCHEDULE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.TokenType;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class StatefulScheduleExecutionTest {
    @HapiTest
    @Order(4)
    final Stream<DynamicTest> scheduledBurnWithInvalidTokenThrowsUnresolvableSigners() {
        return hapiTest(
                cryptoCreate(SCHEDULE_PAYER),
                scheduleCreate(VALID_SCHEDULE, burnToken("0.0.123231", List.of(1L, 2L)))
                        .designatingPayer(SCHEDULE_PAYER)
                        .hasKnownStatus(UNRESOLVABLE_REQUIRED_SIGNERS));
    }

    @LeakyHapiTest(overrides = {"tokens.nfts.areEnabled"})
    final Stream<DynamicTest> scheduledUniqueMintFailsWithNftsDisabled() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(VALID_SCHEDULE, mintToken(A_TOKEN, List.of(ByteString.copyFromUtf8("m1"))))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(FAILING_TXN),
                overriding("tokens.nfts.areEnabled", "false"),
                scheduleSign(VALID_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(NOT_SUPPORTED)),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @LeakyHapiTest(overrides = {"tokens.nfts.areEnabled"})
    final Stream<DynamicTest> scheduledUniqueBurnFailsWithNftsDisabled() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(VALID_SCHEDULE, burnToken(A_TOKEN, List.of(1L, 2L)))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(FAILING_TXN),
                overriding("tokens.nfts.areEnabled", "false"),
                scheduleSign(VALID_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(NOT_SUPPORTED)),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @LeakyHapiTest(overrides = {"ledger.transfers.maxLen"})
    final Stream<DynamicTest> executionWithTransferListWrongSizedFails() {
        long transferAmount = 1L;
        long senderBalance = 1000L;
        long payingAccountBalance = 1_000_000L;
        long noBalance = 0L;
        final var rejectedTxn = "rejectedTxn";
        return hapiTest(
                overriding("ledger.transfers.maxLen", "2"),
                cryptoCreate(PAYING_ACCOUNT).balance(payingAccountBalance),
                cryptoCreate(SENDER).balance(senderBalance),
                cryptoCreate(RECEIVER_A).balance(noBalance),
                cryptoCreate(RECEIVER_B).balance(noBalance),
                cryptoCreate(RECEIVER_C).balance(noBalance),
                scheduleCreate(
                                rejectedTxn,
                                cryptoTransfer(
                                                tinyBarsFromTo(SENDER, RECEIVER_A, transferAmount),
                                                tinyBarsFromTo(SENDER, RECEIVER_B, transferAmount),
                                                tinyBarsFromTo(SENDER, RECEIVER_C, transferAmount))
                                        .memo(randomUppercase(100)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .via("createTx"),
                scheduleSign(rejectedTxn)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via("signTx")
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(senderBalance),
                getAccountBalance(RECEIVER_A).hasTinyBars(noBalance),
                getAccountBalance(RECEIVER_B).hasTinyBars(noBalance),
                getAccountBalance(RECEIVER_C).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord("createTx").scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            TRANSFER_LIST_SIZE_LIMIT_EXCEEDED,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            "Scheduled transaction should not be successful!");
                }));
    }

    @LeakyHapiTest(overrides = {"ledger.tokenTransfers.maxLen"})
    final Stream<DynamicTest> executionWithTokenTransferListSizeExceedFails() {
        String xToken = "XXX";
        String invalidSchedule = "withMaxTokenTransfer";
        String schedulePayer = "somebody", xTreasury = "xt", civilianA = "xa", civilianB = "xb";
        String failedTxn = "bad";

        return hapiTest(
                overriding("ledger.tokenTransfers.maxLen", "2"),
                newKeyNamed("admin"),
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(civilianA),
                cryptoCreate(civilianB),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(100).adminKey("admin"),
                tokenAssociate(civilianA, xToken),
                tokenAssociate(civilianB, xToken),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(2, xToken).distributing(xTreasury, civilianA, civilianB))
                                        .memo(randomUppercase(100)))
                        .via(failedTxn)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTxn)
                        .scheduled()
                        .hasPriority(recordWith().status(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED)),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100));
    }
}
