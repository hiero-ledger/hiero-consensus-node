// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.schedule;

import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTopicInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.grantTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.invalidBurnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.invalidMintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.revokeTokenKyc;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnfreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithInvalidAmounts;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doSeveralWithStartupConfig;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordFeeAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.specOps;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.ADMIN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.A_SCHEDULE;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.A_TOKEN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.BASIC_XFER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.CREATE_TXN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.CREATION;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.FAILED_XFER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.FAILING_TXN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.LUCKY_RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.PAYING_ACCOUNT;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RANDOM_MSG;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.RECEIVER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULED_TRANSACTION_MUST_SUCCEED;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULE_CREATE_FEE;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SCHEDULE_PAYER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER_1;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER_2;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SENDER_3;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIGN_TX;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIGN_TXN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SUCCESS_TXN;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SUPPLY_KEY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TRANSACTION_NOT_SCHEDULED;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.TREASURY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.WEIRDLY_POPULAR_KEY;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.WRONG_CONSENSUS_TIMESTAMP;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.WRONG_RECORD_ACCOUNT_ID;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.WRONG_SCHEDULE_ID;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.WRONG_TRANSACTION_VALID_START;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.WRONG_TRANSFER_LIST;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.scheduledVersionOf;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.transferListCheck;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CHUNK_TRANSACTION_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_METADATA;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MESSAGE_SIZE_TOO_LARGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SOME_SIGNATURES_WERE_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNRESOLVABLE_REQUIRED_SIGNERS;
import static java.lang.Integer.parseInt;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

public class ScheduleExecutionTest {

    private final long normalTriggeredTxnTimestampOffset = 1;

    @SuppressWarnings("java:S2245") // using java.util.Random in tests is fine
    private final Random r = new Random(882654L);

    @HapiTest
    final Stream<DynamicTest> scheduledBurnFailsWithInvalidTxBody() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(A_SCHEDULE, invalidBurnToken(A_TOKEN, List.of(1L, 2L), 123))
                        .designatingPayer(SCHEDULE_PAYER)
                        .hasKnownStatus(INVALID_TRANSACTION_BODY));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledMintFailsWithInvalidTxBody() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(A_SCHEDULE, invalidMintToken(A_TOKEN, List.of(ByteString.copyFromUtf8("m1")), 123))
                        .hasKnownStatus(INVALID_TRANSACTION_BODY)
                        .designatingPayer(SCHEDULE_PAYER),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledMintWithInvalidTokenThrowsUnresolvableSigners() {
        return hapiTest(
                cryptoCreate(SCHEDULE_PAYER),
                scheduleCreate(
                                A_SCHEDULE,
                                mintToken("0.0.123231", List.of(ByteString.copyFromUtf8("m1")))
                                        .fee(ONE_HBAR))
                        .designatingPayer(SCHEDULE_PAYER)
                        .hasKnownStatus(UNRESOLVABLE_REQUIRED_SIGNERS));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledUniqueBurnFailsWithInvalidBatchSize() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                mintToken(A_TOKEN, List.of(ByteString.copyFromUtf8("m1"))),
                scheduleCreate(
                                A_SCHEDULE,
                                burnToken(A_TOKEN, List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L)))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(FAILING_TXN),
                getTokenInfo(A_TOKEN).hasTotalSupply(1),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(BATCH_SIZE_LIMIT_EXCEEDED)),
                getTokenInfo(A_TOKEN).hasTotalSupply(1));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> scheduledUniqueBurnExecutesProperly() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                mintToken(A_TOKEN, List.of(ByteString.copyFromUtf8("metadata"))),
                scheduleCreate(A_SCHEDULE, burnToken(A_TOKEN, List.of(1L)))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(SUCCESS_TXN),
                getTokenInfo(A_TOKEN).hasTotalSupply(1),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(SUCCESS_TXN);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);
                }),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledUniqueMintFailsWithInvalidMetadata() {
        return hapiTest(
                cryptoCreate("payer"),
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(A_SCHEDULE, mintToken(A_TOKEN, List.of(metadataOfLength(101))))
                        .designatingPayer(SCHEDULE_PAYER)
                        .payingWith("payer")
                        .via(FAILING_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(METADATA_TOO_LONG)),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledUniqueBurnFailsWithInvalidNftId() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(A_SCHEDULE, burnToken(A_TOKEN, List.of(123L)))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(FAILING_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(INVALID_NFT_ID)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledBurnForUniqueSucceedsWithExistingAmount() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(A_SCHEDULE, burnToken(A_TOKEN, 123L))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(SUCCESS_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(SUCCESS_TXN).scheduled().hasPriority(recordWith().status(INVALID_TOKEN_BURN_METADATA)),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledBurnForUniqueFailsWithInvalidAmount() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(A_SCHEDULE, burnToken(A_TOKEN, -123L))
                        .designatingPayer(SCHEDULE_PAYER)
                        .hasKnownStatus(INVALID_TOKEN_BURN_AMOUNT),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @LeakyHapiTest(overrides = {"tokens.nfts.maxBatchSizeMint"})
    final Stream<DynamicTest> scheduledUniqueMintFailsWithInvalidBatchSize() {
        return hapiTest(
                overriding("tokens.nfts.maxBatchSizeMint", "5"),
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(
                                A_SCHEDULE,
                                mintToken(
                                        A_TOKEN,
                                        List.of(
                                                ByteString.copyFromUtf8("m1"),
                                                ByteString.copyFromUtf8("m2"),
                                                ByteString.copyFromUtf8("m3"),
                                                ByteString.copyFromUtf8("m4"),
                                                ByteString.copyFromUtf8("m5"),
                                                ByteString.copyFromUtf8("m6"))))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(FAILING_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .hasKnownStatus(SUCCESS),
                getTxnRecord(FAILING_TXN).scheduled().hasPriority(recordWith().status(BATCH_SIZE_LIMIT_EXCEEDED)),
                getTokenInfo(A_TOKEN).hasTotalSupply(0));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledMintFailsWithInvalidAmount() {
        final var zeroAmountTxn = "zeroAmountTxn";

        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN).supplyKey(SUPPLY_KEY).treasury(TREASURY).initialSupply(101),
                scheduleCreate(A_SCHEDULE, mintToken(A_TOKEN, 0))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(zeroAmountTxn),
                scheduleCreate(A_SCHEDULE, mintToken(A_TOKEN, -1))
                        .designatingPayer(SCHEDULE_PAYER)
                        .hasKnownStatus(INVALID_TOKEN_MINT_AMOUNT),
                getTokenInfo(A_TOKEN).hasTotalSupply(101));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledUniqueMintExecutesProperly() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .initialSupply(0),
                scheduleCreate(
                                A_SCHEDULE,
                                mintToken(
                                        A_TOKEN,
                                        List.of(
                                                ByteString.copyFromUtf8("somemetadata1"),
                                                ByteString.copyFromUtf8("somemetadata2"))))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(SUCCESS_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(SUCCESS_TXN);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);
                }),
                getTokenInfo(A_TOKEN).hasTotalSupply(2));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledMintExecutesProperly() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN).supplyKey(SUPPLY_KEY).treasury(TREASURY).initialSupply(101),
                scheduleCreate(A_SCHEDULE, mintToken(A_TOKEN, 10))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(SUCCESS_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(SUCCESS_TXN);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);
                }),
                getTokenInfo(A_TOKEN).hasTotalSupply(111));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithFrozenAccountTransferPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String validSchedule = "withUnfrozenAccount";
        String invalidSchedule = "withFrozenAccount";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String xCivilian = "xc";
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                newKeyNamed("freeze"),
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(xCivilian),
                tokenCreate(xToken)
                        .treasury(xTreasury)
                        .initialSupply(101)
                        .freezeKey("freeze")
                        .freezeDefault(true),
                tokenAssociate(xCivilian, xToken),
                tokenUnfreeze(xToken, xCivilian),
                scheduleCreate(
                                validSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .memo(randomUppercase(100)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                tokenFreeze(xToken, xCivilian),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .memo(randomUppercase(100)))
                        .via(failedTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(ACCOUNT_FROZEN_FOR_TOKEN))
                        .revealingDebitsTo(failureFeesObs::set),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledBurnExecutesProperly() {
        return hapiTest(
                cryptoCreate(TREASURY),
                cryptoCreate(SCHEDULE_PAYER),
                newKeyNamed(SUPPLY_KEY),
                tokenCreate(A_TOKEN)
                        .supplyKey(SUPPLY_KEY)
                        .treasury(TREASURY)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(101),
                scheduleCreate(A_SCHEDULE, burnToken(A_TOKEN, 10))
                        .designatingPayer(SCHEDULE_PAYER)
                        .via(SUCCESS_TXN),
                scheduleSign(A_SCHEDULE)
                        .alsoSigningWith(SUPPLY_KEY, SCHEDULE_PAYER, TREASURY)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(SUCCESS_TXN);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);
                }),
                getTokenInfo(A_TOKEN).hasTotalSupply(91));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithDeletedAccountPaysServiceFeeButNoImpact() {
        final String xToken = "XXX";
        final String validSchedule = "withLiveAccount";
        final String invalidSchedule = "withDeletedAccount";
        final String schedulePayer = PAYER;
        final String xTreasury = "xt";
        final String xCivilian = "xc";
        final String deadXCivilian = "deadxc";
        final String successTx = "good";
        final String failedTx = "bad";
        final AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        final AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(xCivilian),
                cryptoCreate(deadXCivilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101),
                tokenAssociate(xCivilian, xToken),
                tokenAssociate(deadXCivilian, xToken),
                scheduleCreate(validSchedule, cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                cryptoDelete(deadXCivilian),
                scheduleCreate(invalidSchedule, cryptoTransfer(moving(1, xToken).between(xTreasury, deadXCivilian)))
                        .via(failedTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(ACCOUNT_DELETED))
                        .revealingDebitsTo(failureFeesObs::set),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithDeletedTokenPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String validSchedule = "withLiveToken";
        String invalidSchedule = "withDeletedToken";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String xCivilian = "xc";
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(xCivilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101).adminKey(ADMIN),
                tokenAssociate(xCivilian, xToken),
                scheduleCreate(
                                validSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .memo(randomUppercase(100)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                tokenDelete(xToken),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .memo(randomUppercase(100)))
                        .via(failedTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(TOKEN_WAS_DELETED))
                        .revealingDebitsTo(failureFeesObs::set),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> scheduledXferFailingWithNonKycedAccountTransferPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String validSchedule = "withKycedToken";
        String invalidSchedule = "withNonKycedToken";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String xCivilian = "xc";
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                newKeyNamed("kyc"),
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(xCivilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101).kycKey("kyc"),
                tokenAssociate(xCivilian, xToken),
                grantTokenKyc(xToken, xCivilian),
                scheduleCreate(
                                validSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .memo(randomUppercase(100)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                revokeTokenKyc(xToken, xCivilian),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .memo(randomUppercase(100)))
                        .via(failedTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN))
                        .revealingDebitsTo(failureFeesObs::set),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithUnassociatedAccountTransferPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String validSchedule = "withAssociatedToken";
        String invalidSchedule = "withUnassociatedToken";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String xCivilian = "xc";
        String nonXCivilian = "nxc";
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(xCivilian),
                cryptoCreate(nonXCivilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101),
                tokenAssociate(xCivilian, xToken),
                scheduleCreate(validSchedule, cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                scheduleCreate(invalidSchedule, cryptoTransfer(moving(1, xToken).between(xTreasury, nonXCivilian)))
                        .via(failedTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .revealingDebitsTo(failureFeesObs::set),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountInfo(nonXCivilian).hasNoTokenRelationship(xToken),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithNonNetZeroTokenTransferPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String validSchedule = "withZeroNetTokenChange";
        String invalidSchedule = "withNonZeroNetTokenChange";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String xCivilian = "xc";
        String successTx = "good";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(xCivilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101),
                tokenAssociate(xCivilian, xToken),
                scheduleCreate(validSchedule, cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, xCivilian))
                                        .breakingNetZeroInvariant())
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer)
                        .hasKnownStatus(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xCivilian).hasTokenBalance(xToken, 1));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithRepeatedTokenIdPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String yToken = "YYY";
        String validSchedule = "withNoRepeats";
        String invalidSchedule = "withRepeats";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String yTreasury = "yt";
        String successTx = "good";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(yTreasury),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101),
                tokenCreate(yToken).treasury(yTreasury).initialSupply(101),
                tokenAssociate(xTreasury, yToken),
                tokenAssociate(yTreasury, xToken),
                scheduleCreate(
                                validSchedule,
                                cryptoTransfer(
                                        moving(1, xToken).between(xTreasury, yTreasury),
                                        moving(1, yToken).between(yTreasury, xTreasury)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, yTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xTreasury).hasTokenBalance(yToken, 1),
                getAccountBalance(yTreasury).hasTokenBalance(yToken, 100),
                getAccountBalance(yTreasury).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(1, xToken).between(xTreasury, yTreasury))
                                        .appendingTokenFromTo(xToken, xTreasury, yTreasury, 1))
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer)
                        .hasKnownStatus(TOKEN_ID_REPEATED_IN_TOKEN_LIST),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xTreasury).hasTokenBalance(yToken, 1),
                getAccountBalance(yTreasury).hasTokenBalance(yToken, 100),
                getAccountBalance(yTreasury).hasTokenBalance(xToken, 1));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledXferFailingWithEmptyTokenTransferAccountAmountsPaysServiceFeeButNoImpact() {
        String xToken = "XXX";
        String yToken = "YYY";
        String validSchedule = "withNonEmptyTransfers";
        String invalidSchedule = "withEmptyTransfer";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String yTreasury = "yt";
        String xyCivilian = "xyt";
        String successTx = "good";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(yTreasury),
                cryptoCreate(xyCivilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(101),
                tokenCreate(yToken).treasury(yTreasury).initialSupply(101),
                tokenAssociate(xTreasury, yToken),
                tokenAssociate(yTreasury, xToken),
                scheduleCreate(
                                validSchedule,
                                cryptoTransfer(
                                        moving(1, xToken).between(xTreasury, yTreasury),
                                        moving(1, yToken).between(yTreasury, xTreasury)))
                        .via(successTx)
                        .alsoSigningWith(xTreasury, yTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xTreasury).hasTokenBalance(yToken, 1),
                getAccountBalance(yTreasury).hasTokenBalance(yToken, 100),
                getAccountBalance(yTreasury).hasTokenBalance(xToken, 1),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(2, xToken).distributing(xTreasury, yTreasury, xyCivilian))
                                        .withEmptyTokenTransfers(yToken))
                        .alsoSigningWith(xTreasury, yTreasury, schedulePayer)
                        .designatingPayer(schedulePayer)
                        .hasKnownStatus(EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100),
                getAccountBalance(xTreasury).hasTokenBalance(yToken, 1),
                getAccountBalance(yTreasury).hasTokenBalance(yToken, 100),
                getAccountBalance(yTreasury).hasTokenBalance(xToken, 1));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledSubmitFailedWithMsgSizeTooLargeStillPaysServiceFeeButHasNoImpact() {
        String immutableTopic = "XXX";
        String validSchedule = "withValidSize";
        String invalidSchedule = "withInvalidSize";
        String schedulePayer = PAYER;
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                createTopic(immutableTopic),
                cryptoCreate(schedulePayer),
                doSeveralWithStartupConfig("consensus.message.maxBytesAllowed", value -> {
                    final var maxValidLen = parseInt(value);
                    return specOps(
                            scheduleCreate(
                                            validSchedule,
                                            submitMessageTo(immutableTopic).message(randomUppercase(maxValidLen)))
                                    .designatingPayer(schedulePayer)
                                    .via(successTx)
                                    .signedBy(DEFAULT_PAYER, schedulePayer),
                            getTopicInfo(immutableTopic).hasSeqNo(1L),
                            getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                            scheduleCreate(
                                            invalidSchedule,
                                            submitMessageTo(immutableTopic).message(randomUppercase(maxValidLen + 1)))
                                    .designatingPayer(schedulePayer)
                                    .via(failedTx)
                                    .signedBy(DEFAULT_PAYER, schedulePayer));
                }),
                getTopicInfo(immutableTopic).hasSeqNo(1L),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(MESSAGE_SIZE_TOO_LARGE))
                        .revealingDebitsTo(failureFeesObs::set),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledSubmitFailedWithInvalidChunkTxnIdStillPaysServiceFeeButHasNoImpact() {
        String immutableTopic = "XXX";
        String validSchedule = "withValidChunkTxnId";
        String invalidSchedule = "withInvalidChunkTxnId";
        String schedulePayer = PAYER;
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();
        AtomicReference<TransactionID> initialTxnId = new AtomicReference<>();
        AtomicReference<TransactionID> irrelevantTxnId = new AtomicReference<>();

        return hapiTest(
                createTopic(immutableTopic),
                cryptoCreate(schedulePayer),
                withOpContext((spec, opLog) -> {
                    var subOp = usableTxnIdNamed(successTx).payerId(schedulePayer);
                    var secondSubOp = usableTxnIdNamed("wontWork").payerId(schedulePayer);
                    allRunFor(spec, subOp, secondSubOp);
                    initialTxnId.set(spec.registry().getTxnId(successTx));
                    irrelevantTxnId.set(spec.registry().getTxnId("wontWork"));
                }),
                sourcing(() -> scheduleCreate(
                                validSchedule,
                                submitMessageTo(immutableTopic).chunkInfo(3, 1, scheduledVersionOf(initialTxnId.get())))
                        .txnId(successTx)
                        .logged()
                        .signedBy(schedulePayer)),
                getTopicInfo(immutableTopic).hasSeqNo(1L),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                sourcing(() -> scheduleCreate(
                                invalidSchedule,
                                submitMessageTo(immutableTopic)
                                        .chunkInfo(3, 1, scheduledVersionOf(irrelevantTxnId.get())))
                        .designatingPayer(schedulePayer)
                        .via(failedTx)
                        .logged()
                        .signedBy(DEFAULT_PAYER, schedulePayer)),
                getTopicInfo(immutableTopic).hasSeqNo(1L),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(INVALID_CHUNK_TRANSACTION_ID))
                        .revealingDebitsTo(failureFeesObs::set),
                assertionsHold((spec, opLog) -> Assertions.assertEquals(successFeesObs.get(), failureFeesObs.get())));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledSubmitFailedWithInvalidChunkNumberStillPaysServiceFeeButHasNoImpact() {
        String immutableTopic = "XXX";
        String validSchedule = "withValidChunkNumber";
        String invalidSchedule = "withInvalidChunkNumber";
        String schedulePayer = PAYER;
        String successTx = "good";
        String failedTx = "bad";
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();
        AtomicReference<TransactionID> initialTxnId = new AtomicReference<>();

        return hapiTest(
                createTopic(immutableTopic),
                cryptoCreate(schedulePayer),
                withOpContext((spec, opLog) -> {
                    var subOp = usableTxnIdNamed(successTx).payerId(schedulePayer);
                    allRunFor(spec, subOp);
                    initialTxnId.set(spec.registry().getTxnId(successTx));
                }),
                sourcing(() -> scheduleCreate(
                                validSchedule,
                                submitMessageTo(immutableTopic).chunkInfo(3, 1, scheduledVersionOf(initialTxnId.get())))
                        .txnId(successTx)
                        .logged()
                        .signedBy(schedulePayer)),
                getTopicInfo(immutableTopic).hasSeqNo(1L),
                getTxnRecord(successTx).scheduled().logged().revealingDebitsTo(successFeesObs::set),
                scheduleCreate(invalidSchedule, submitMessageTo(immutableTopic).chunkInfo(3, 111, schedulePayer))
                        .via(failedTx)
                        .logged()
                        .payingWith(schedulePayer),
                getTopicInfo(immutableTopic).hasSeqNo(1L),
                getTxnRecord(failedTx)
                        .scheduled()
                        .hasPriority(recordWith().status(INVALID_CHUNK_NUMBER))
                        .revealingDebitsTo(failureFeesObs::set),
                assertionsHold((spec, opLog) -> Assertions.assertEquals(successFeesObs.get(), failureFeesObs.get())));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledSubmitThatWouldFailWithInvalidTopicIdCannotBeScheduled() {
        String civilianPayer = PAYER;
        AtomicReference<Map<AccountID, Long>> successFeesObs = new AtomicReference<>();
        AtomicReference<Map<AccountID, Long>> failureFeesObs = new AtomicReference<>();

        return hapiTest(
                cryptoCreate(civilianPayer),
                createTopic("fascinating"),
                scheduleCreate("yup", submitMessageTo("fascinating").message(RANDOM_MSG))
                        .payingWith(civilianPayer)
                        .via(CREATION),
                scheduleCreate("nope", submitMessageTo("1.2.3").message(RANDOM_MSG))
                        .payingWith(civilianPayer)
                        .via("nothingShouldBeCreated")
                        .hasKnownStatus(UNRESOLVABLE_REQUIRED_SIGNERS),
                getTxnRecord(CREATION).revealingDebitsTo(successFeesObs::set),
                getTxnRecord("nothingShouldBeCreated")
                        .revealingDebitsTo(failureFeesObs::set)
                        .logged(),
                assertionsHold(
                        (spec, opLog) -> assertBasicallyIdentical(successFeesObs.get(), failureFeesObs.get(), 1.0)));
    }

    @HapiTest
    final Stream<DynamicTest> scheduledSubmitThatWouldFailWithTopicDeletedCannotBeSigned() {
        String adminKey = ADMIN;
        String mutableTopic = "XXX";
        String postDeleteSchedule = "deferredTooLongSubmitMsg";
        String schedulePayer = PAYER;
        String failedTxn = "deleted";

        return hapiTest(
                newKeyNamed(adminKey),
                createTopic(mutableTopic).adminKeyName(adminKey),
                cryptoCreate(schedulePayer),
                scheduleCreate(postDeleteSchedule, submitMessageTo(mutableTopic).message(RANDOM_MSG))
                        .designatingPayer(schedulePayer)
                        .payingWith(DEFAULT_PAYER)
                        .via(failedTxn),
                deleteTopic(mutableTopic),
                scheduleSign(postDeleteSchedule)
                        .alsoSigningWith(schedulePayer)
                        .hasKnownStatus(UNRESOLVABLE_REQUIRED_SIGNERS));
    }

    @HapiTest
    final Stream<DynamicTest> executionTriggersOnceTopicHasSatisfiedSubmitKey() {
        String adminKey = ADMIN;
        String submitKey = "submit";
        String mutableTopic = "XXX";
        String schedule = "deferredSubmitMsg";

        return hapiTest(
                newKeyNamed(adminKey),
                newKeyNamed(submitKey),
                createTopic(mutableTopic).adminKeyName(adminKey).submitKeyName(submitKey),
                cryptoCreate(PAYER),
                scheduleCreate(schedule, submitMessageTo(mutableTopic).message(RANDOM_MSG))
                        .designatingPayer(PAYER)
                        .payingWith(DEFAULT_PAYER)
                        .alsoSigningWith(PAYER)
                        .via(CREATION),
                getTopicInfo(mutableTopic).hasSeqNo(0L),
                scheduleSign(schedule)
                        .alsoSigningWith(adminKey)
                        /* In the rare, but possible, case that the adminKey and submitKey keys overlap
                         * in their first byte (and that byte is not shared by the DEFAULT_PAYER),
                         * we will get SOME_SIGNATURES_WERE_INVALID instead of NO_NEW_VALID_SIGNATURES.
                         *
                         * So we need this to stabilize CI. But if just testing locally, you may
                         * only use .hasKnownStatus(NO_NEW_VALID_SIGNATURES) and it will pass
                         * >99.99% of the time. */
                        .hasKnownStatusFrom(NO_NEW_VALID_SIGNATURES, SOME_SIGNATURES_WERE_INVALID),
                updateTopic(mutableTopic).submitKey(PAYER),
                scheduleSign(schedule),
                getScheduleInfo(schedule).isExecuted(),
                getTopicInfo(mutableTopic).hasSeqNo(1L));
    }

    @HapiTest
    final Stream<DynamicTest> executionTriggersWithWeirdlyRepeatedKey() {
        String schedule = "dupKeyXfer";

        return hapiTest(
                cryptoCreate(WEIRDLY_POPULAR_KEY),
                cryptoCreate(SENDER_1).key(WEIRDLY_POPULAR_KEY).balance(1L),
                cryptoCreate(SENDER_2).key(WEIRDLY_POPULAR_KEY).balance(1L),
                cryptoCreate(SENDER_3).key(WEIRDLY_POPULAR_KEY).balance(1L),
                cryptoCreate(RECEIVER).balance(0L),
                scheduleCreate(
                                schedule,
                                cryptoTransfer(
                                        tinyBarsFromTo(SENDER_1, RECEIVER, 1L),
                                        tinyBarsFromTo(SENDER_2, RECEIVER, 1L),
                                        tinyBarsFromTo(SENDER_3, RECEIVER, 1L)))
                        .payingWith(DEFAULT_PAYER)
                        .via(CREATION),
                scheduleSign(schedule).alsoSigningWith(WEIRDLY_POPULAR_KEY),
                getScheduleInfo(schedule).isExecuted(),
                getAccountBalance(SENDER_1).hasTinyBars(0L),
                getAccountBalance(SENDER_2).hasTinyBars(0L),
                getAccountBalance(SENDER_3).hasTinyBars(0L),
                getAccountBalance(RECEIVER).hasTinyBars(3L),
                scheduleSign(schedule)
                        .via("repeatedSigning")
                        .alsoSigningWith(WEIRDLY_POPULAR_KEY)
                        .hasKnownStatus(SCHEDULE_ALREADY_EXECUTED),
                getTxnRecord("repeatedSigning").logged());
    }

    @HapiTest
    final Stream<DynamicTest> executionWithDefaultPayerWorks() {
        long transferAmount = 1;
        return hapiTest(
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                cryptoCreate(PAYING_ACCOUNT),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .payingWith(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TXN),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TXN);
                    var signTx = getTxnRecord(SIGN_TXN).logged();
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);

                    Assertions.assertTrue(
                            transferListCheck(
                                    triggeredTx,
                                    asId(SENDER, spec),
                                    asId(RECEIVER, spec),
                                    asId(PAYING_ACCOUNT, spec),
                                    transferAmount),
                            WRONG_TRANSFER_LIST);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithDefaultPayerButNoFundsFails() {
        long balance = 10_000_000L;
        long noBalance = 0L;
        long transferAmount = 1L;

        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(LUCKY_RECEIVER),
                cryptoCreate(SENDER).balance(transferAmount),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .payingWith(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                recordFeeAmount(CREATE_TXN, SCHEDULE_CREATE_FEE),
                cryptoTransfer(tinyBarsFromTo(PAYING_ACCOUNT, LUCKY_RECEIVER, (spec -> {
                    long scheduleCreateFee = spec.registry().getAmount(SCHEDULE_CREATE_FEE);
                    return balance - scheduleCreateFee;
                }))),
                getAccountBalance(PAYING_ACCOUNT).hasTinyBars(noBalance),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            INSUFFICIENT_PAYER_BALANCE,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCustomPayerWorksWithLastSigBeingCustomPayer() {
        long noBalance = 0L;
        long transferAmount = 1;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(SENDER).balance(transferAmount),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TXN).hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(PAYING_ACCOUNT)
                        .via(SIGN_TXN)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                }),
                getAccountBalance(SENDER).hasTinyBars(noBalance),
                getAccountBalance(RECEIVER).hasTinyBars(transferAmount));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCustomPayerButNoFundsFails() {
        long balance = 0L;
        long noBalance = 0L;
        long transferAmount = 1;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(SENDER).balance(transferAmount),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TXN)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            INSUFFICIENT_PAYER_BALANCE,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithDefaultPayerButAccountDeletedFails() {
        long balance = 10_000_000L;
        long noBalance = 0L;
        long transferAmount = 1L;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(LUCKY_RECEIVER),
                cryptoCreate(SENDER).balance(transferAmount),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .payingWith(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                recordFeeAmount(CREATE_TXN, SCHEDULE_CREATE_FEE),
                cryptoDelete(PAYING_ACCOUNT),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER).isExecuted(),
                getTxnRecord(CREATE_TXN)
                        .scheduled()
                        .hasPriority(recordWith().statusFrom(INSUFFICIENT_PAYER_BALANCE, PAYER_ACCOUNT_DELETED)));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCustomPayerButAccountDeletedFails() {
        long balance = 10_000_000L;
        long noBalance = 0L;
        long transferAmount = 1;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(SENDER).balance(transferAmount),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .alsoSigningWith(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                cryptoDelete(PAYING_ACCOUNT),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TXN).hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                getScheduleInfo(BASIC_XFER).isExecuted(),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();

                    allRunFor(spec, triggeredTx);

                    final var failureReasons = EnumSet.of(INSUFFICIENT_PAYER_BALANCE, PAYER_ACCOUNT_DELETED);
                    Assertions.assertTrue(
                            failureReasons.contains(
                                    triggeredTx.getResponseRecord().getReceipt().getStatus()),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED + " for one of reasons " + failureReasons);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCryptoInsufficientAccountBalanceFails() {
        long noBalance = 0L;
        long senderBalance = 100L;
        long transferAmount = 101L;
        long payerBalance = 1_000_000L;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(payerBalance),
                cryptoCreate(SENDER).balance(senderBalance),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(FAILED_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(FAILED_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TXN)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(senderBalance),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            INSUFFICIENT_ACCOUNT_BALANCE,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCryptoSenderDeletedFails() {
        long noBalance = 0L;
        long senderBalance = 100L;
        long transferAmount = 101L;
        long payerBalance = 1_000_000L;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(payerBalance),
                cryptoCreate(SENDER).balance(senderBalance),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(FAILED_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                cryptoDelete(SENDER),
                scheduleSign(FAILED_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TXN)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                getScheduleInfo(FAILED_XFER).isExecuted(),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            ACCOUNT_DELETED,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithTokenInsufficientAccountBalanceFails() {
        String xToken = "XXX";
        String invalidSchedule = "withInsufficientTokenTransfer";
        String schedulePayer = PAYER;
        String xTreasury = "xt";
        String civilian = "xa";
        String failedTxn = "bad";
        return hapiTest(
                newKeyNamed(ADMIN),
                cryptoCreate(schedulePayer),
                cryptoCreate(xTreasury),
                cryptoCreate(civilian),
                tokenCreate(xToken).treasury(xTreasury).initialSupply(100).adminKey(ADMIN),
                tokenAssociate(civilian, xToken),
                scheduleCreate(
                                invalidSchedule,
                                cryptoTransfer(moving(101, xToken).between(xTreasury, civilian))
                                        .memo(randomUppercase(100)))
                        .via(failedTxn)
                        .alsoSigningWith(xTreasury, schedulePayer)
                        .designatingPayer(schedulePayer),
                getTxnRecord(failedTxn).scheduled().hasPriority(recordWith().status(INSUFFICIENT_TOKEN_BALANCE)),
                getAccountBalance(xTreasury).hasTokenBalance(xToken, 100));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithInvalidAccountAmountsFails() {
        long transferAmount = 100;
        long senderBalance = 1000L;
        long payingAccountBalance = 1_000_000L;
        long noBalance = 0L;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT).balance(payingAccountBalance),
                cryptoCreate(SENDER).balance(senderBalance),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(
                                FAILED_XFER,
                                cryptoTransfer(tinyBarsFromToWithInvalidAmounts(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .hasKnownStatus(INVALID_ACCOUNT_AMOUNTS),
                getAccountBalance(SENDER).hasTinyBars(senderBalance),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCustomPayerWorks() {
        long transferAmount = 1;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TXN)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TXN);
                    var signTx = getTxnRecord(SIGN_TXN);
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_SUCCEED);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);

                    Assertions.assertTrue(
                            transferListCheck(
                                    triggeredTx,
                                    asId(SENDER, spec),
                                    asId(RECEIVER, spec),
                                    asId(PAYING_ACCOUNT, spec),
                                    transferAmount),
                            WRONG_TRANSFER_LIST);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCustomPayerAndAdminKeyWorks() {
        long transferAmount = 1;
        return hapiTest(
                newKeyNamed("adminKey"),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .adminKey("adminKey")
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TXN)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TXN);
                    var signTx = getTxnRecord(SIGN_TXN);
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_SUCCEED);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);

                    Assertions.assertTrue(
                            transferListCheck(
                                    triggeredTx,
                                    asId(SENDER, spec),
                                    asId(RECEIVER, spec),
                                    asId(PAYING_ACCOUNT, spec),
                                    transferAmount),
                            WRONG_TRANSFER_LIST);
                }));
    }

    @HapiTest
    final Stream<DynamicTest> executionWithCustomPayerWhoSignsAtCreationAsPayerWorks() {
        long transferAmount = 1;
        return hapiTest(
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(SENDER),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .payingWith(PAYING_ACCOUNT)
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TXN),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TXN).hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TXN);
                    var signTx = getTxnRecord(SIGN_TXN);
                    var triggeredTx = getTxnRecord(CREATE_TXN).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_SUCCEED);

                    Assertions.assertEquals(
                            signTx.getResponseRecord().getConsensusTimestamp().getNanos()
                                    + normalTriggeredTxnTimestampOffset,
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos(),
                            WRONG_CONSENSUS_TIMESTAMP);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                            WRONG_TRANSACTION_VALID_START);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getTransactionID().getAccountID(),
                            triggeredTx.getResponseRecord().getTransactionID().getAccountID(),
                            WRONG_RECORD_ACCOUNT_ID);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getTransactionID().getScheduled(),
                            TRANSACTION_NOT_SCHEDULED);

                    Assertions.assertEquals(
                            createTx.getResponseRecord().getReceipt().getScheduleID(),
                            triggeredTx.getResponseRecord().getScheduleRef(),
                            WRONG_SCHEDULE_ID);

                    Assertions.assertTrue(
                            transferListCheck(
                                    triggeredTx,
                                    asId(SENDER, spec),
                                    asId(RECEIVER, spec),
                                    asId(PAYING_ACCOUNT, spec),
                                    transferAmount),
                            WRONG_TRANSFER_LIST);
                }));
    }

    private ByteString metadataOfLength(int length) {
        return ByteString.copyFrom(genRandomBytes(length));
    }

    private byte[] genRandomBytes(int numBytes) {
        byte[] contents = new byte[numBytes];
        r.nextBytes(contents);
        return contents;
    }

    private void assertBasicallyIdentical(
            Map<AccountID, Long> aFees, Map<AccountID, Long> bFees, double allowedPercentDeviation) {
        Assertions.assertEquals(aFees.keySet(), bFees.keySet());
        for (var id : aFees.keySet()) {
            long a = aFees.get(id);
            long b = bFees.get(id);
            Assertions.assertEquals(100.0, (1.0 * a) / b * 100.0, allowedPercentDeviation);
        }
    }
}
