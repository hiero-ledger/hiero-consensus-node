// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.randomUppercase;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemFileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeAbort;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordFeeAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.uploadScheduledContractPrices;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FREEZE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_DELETE_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.THREE_MONTHS_IN_SECONDS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.freeze.UpgradeSuite.standardUpdateFile;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.ORIG_FILE;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.PAYING_ACCOUNT_2;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SCHEDULE_CREATE_FEE;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SENDER_1;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SENDER_2;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SENDER_3;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SIMPLE_UPDATE;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.SUCCESS_TXN;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.TRANSACTION_NOT_SCHEDULED;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.TRIGGERING_TXN;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.VALID_SCHEDULE;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.WEIRDLY_POPULAR_KEY;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.WRONG_CONSENSUS_TIMESTAMP;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.WRONG_RECORD_ACCOUNT_ID;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.WRONG_SCHEDULE_ID;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.WRONG_TRANSACTION_VALID_START;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.WRONG_TRANSFER_LIST;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.scheduleFakeUpgrade;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.transferListCheck;
import static com.hedera.services.bdd.suites.hip423.LongTermScheduleUtils.triggerSchedule;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Order(4)
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RepeatableScheduleLongTermExecutionTest {
    private static final String PAYING_ACCOUNT = "payingAccount";
    private static final String RECEIVER = "receiver";
    private static final String SENDER = "sender";
    private static final String SENDER_TXN = "senderTxn";
    private static final String PAYING_ACCOUNT_TXN = "payingAccountTxn";
    private static final String LUCKY_RECEIVER = "luckyReceiver";
    private static final String FAILED_XFER = "failedXfer";
    private static final String WEIRDLY_POPULAR_KEY_TXN = "weirdlyPopularKeyTxn";
    private static final String PAYER_TXN = "payerTxn";
    private static final String FILE_NAME = "misc";
    private static final long PAYER_INITIAL_BALANCE = 1000000000000L;

    public static final String BASIC_XFER = "basicXfer";
    public static final String CREATE_TX = "createTxn";
    public static final String SIGN_TX = "sign_tx";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle lifecycle) {
        // override and preserve old values
        lifecycle.overrideInClass(Map.of(
                "scheduling.longTermEnabled",
                "true",
                "scheduling.whitelist",
                "ConsensusSubmitMessage,CryptoTransfer,TokenMint,TokenBurn,"
                        + "CryptoCreate,CryptoUpdate,FileUpdate,SystemDelete,SystemUndelete,"
                        + "Freeze,ContractCall,ContractCreate,ContractUpdate,ContractDelete"));
    }

    @SuppressWarnings("java:S5960")
    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> executionWithCustomPayerWorks() {
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(RECEIVER),
                cryptoCreate(SENDER).via(SENDER_TXN),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TX);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeringTx = getTxnRecord(TRIGGERING_TXN);
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx, triggeringTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);

                    Instant triggerTime = Instant.ofEpochSecond(
                            triggeringTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getSeconds(),
                            triggeringTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos());

                    Instant triggeredTime = Instant.ofEpochSecond(
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getSeconds(),
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos());

                    Assertions.assertTrue(triggerTime.isBefore(triggeredTime), WRONG_CONSENSUS_TIMESTAMP);

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
                                    1L),
                            WRONG_TRANSFER_LIST);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> executionWithCustomPayerAndAdminKeyWorks() {
        return hapiTest(flattened(
                newKeyNamed("adminKey"),
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(RECEIVER),
                cryptoCreate(SENDER).via(SENDER_TXN),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .designatingPayer(PAYING_ACCOUNT)
                        .adminKey("adminKey")
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TX);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeringTx = getTxnRecord(TRIGGERING_TXN);
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx, triggeringTx);
                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);

                    Instant triggerTime = Instant.ofEpochSecond(
                            triggeringTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getSeconds(),
                            triggeringTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos());

                    Instant triggeredTime = Instant.ofEpochSecond(
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getSeconds(),
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos());

                    Assertions.assertTrue(triggerTime.isBefore(triggeredTime), WRONG_CONSENSUS_TIMESTAMP);

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
                                    1L),
                            WRONG_TRANSFER_LIST);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> executionWithCustomPayerWhoSignsAtCreationAsPayerWorks() {
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(RECEIVER),
                cryptoCreate(SENDER).via(SENDER_TXN),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, 1)))
                        .payingWith(PAYING_ACCOUNT)
                        .designatingPayer(PAYING_ACCOUNT)
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TX).hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                withOpContext((spec, opLog) -> {
                    var createTx = getTxnRecord(CREATE_TX);
                    var signTx = getTxnRecord(SIGN_TX);
                    var triggeringTx = getTxnRecord(TRIGGERING_TXN);
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();
                    allRunFor(spec, createTx, signTx, triggeredTx, triggeringTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);

                    Instant triggerTime = Instant.ofEpochSecond(
                            triggeringTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getSeconds(),
                            triggeringTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos());

                    Instant triggeredTime = Instant.ofEpochSecond(
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getSeconds(),
                            triggeredTx
                                    .getResponseRecord()
                                    .getConsensusTimestamp()
                                    .getNanos());

                    Assertions.assertTrue(triggerTime.isBefore(triggeredTime), WRONG_CONSENSUS_TIMESTAMP);

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
                                    1L),
                            WRONG_TRANSFER_LIST);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithContractCallWorksAtExpiry() {
        final var payerBalance = new AtomicLong();
        return hapiTest(flattened(
                // upload fees for SCHEDULE_CREATE_CONTRACT_CALL
                uploadScheduledContractPrices(GENESIS),
                uploadInitCode(SIMPLE_UPDATE),
                contractCreate(SIMPLE_UPDATE).gas(500_000L),
                cryptoCreate(PAYING_ACCOUNT).balance(PAYER_INITIAL_BALANCE).via(PAYING_ACCOUNT_TXN),
                scheduleCreate(
                                BASIC_XFER,
                                contractCall(SIMPLE_UPDATE, "set", BigInteger.valueOf(5), BigInteger.valueOf(42))
                                        .gas(300000L))
                        .waitForExpiry()
                        .withRelativeExpiry(PAYING_ACCOUNT_TXN, 4)
                        .designatingPayer(PAYING_ACCOUNT)
                        .alsoSigningWith(PAYING_ACCOUNT)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(PAYING_ACCOUNT_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                getAccountBalance(PAYING_ACCOUNT)
                        .hasTinyBars(spec ->
                                bal -> bal < PAYER_INITIAL_BALANCE ? Optional.empty() : Optional.of("didnt change"))
                        .exposingBalanceTo(payerBalance::set),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();
                    allRunFor(spec, triggeredTx);
                    final var txnFee = triggeredTx.getResponseRecord().getTransactionFee();
                    // check if only designating payer was charged
                    Assertions.assertEquals(PAYER_INITIAL_BALANCE, txnFee + payerBalance.get());

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);

                    Assertions.assertTrue(triggeredTx
                                    .getResponseRecord()
                                    .getContractCallResult()
                                    .getContractCallResult()
                                    .size()
                            >= 0);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithContractCreateWorksAtExpiry() {
        final var payerBalance = new AtomicLong();
        return hapiTest(flattened(
                uploadInitCode(SIMPLE_UPDATE),
                cryptoCreate(PAYING_ACCOUNT).balance(PAYER_INITIAL_BALANCE).via(PAYING_ACCOUNT_TXN),
                scheduleCreate(
                                BASIC_XFER,
                                contractCreate(SIMPLE_UPDATE).gas(500_000L).adminKey(PAYING_ACCOUNT))
                        .waitForExpiry()
                        .withRelativeExpiry(PAYING_ACCOUNT_TXN, 4)
                        .designatingPayer(PAYING_ACCOUNT)
                        .alsoSigningWith(PAYING_ACCOUNT)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(PAYING_ACCOUNT_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                getAccountBalance(PAYING_ACCOUNT)
                        .hasTinyBars(spec ->
                                bal -> bal < PAYER_INITIAL_BALANCE ? Optional.empty() : Optional.of("didnt change"))
                        .exposingBalanceTo(payerBalance::set),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();
                    allRunFor(spec, triggeredTx);
                    final var txnFee = triggeredTx.getResponseRecord().getTransactionFee();
                    // check if only designating payer was charged
                    Assertions.assertEquals(PAYER_INITIAL_BALANCE, txnFee + payerBalance.get());

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);

                    Assertions.assertTrue(
                            triggeredTx.getResponseRecord().getReceipt().hasContractID());

                    Assertions.assertTrue(triggeredTx
                                    .getResponseRecord()
                                    .getContractCreateResult()
                                    .getContractCallResult()
                                    .size()
                            >= 0);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithDefaultPayerButNoFundsFails() {
        long balance = 10_000_000L;
        long noBalance = 0L;
        long transferAmount = 1L;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(LUCKY_RECEIVER),
                cryptoCreate(SENDER).balance(transferAmount).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 10)
                        .payingWith(PAYING_ACCOUNT)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                recordFeeAmount(CREATE_TX, SCHEDULE_CREATE_FEE),
                cryptoTransfer(tinyBarsFromTo(PAYING_ACCOUNT, LUCKY_RECEIVER, (spec -> {
                    long scheduleCreateFee = spec.registry().getAmount(SCHEDULE_CREATE_FEE);
                    return balance - scheduleCreateFee;
                }))),
                getAccountBalance(PAYING_ACCOUNT).hasTinyBars(noBalance),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 10)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER, 15),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            INSUFFICIENT_PAYER_BALANCE,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithCustomPayerThatNeverSignsFails() {
        long transferAmount = 1;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT),
                cryptoCreate(SENDER).via(SENDER_TXN),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TX),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TX).hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                getTxnRecord(CREATE_TX).scheduled().hasPriority(recordWith().status(INVALID_PAYER_SIGNATURE))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithCustomPayerButNoFundsFails() {
        long balance = 0L;
        long transferAmount = 1;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(SENDER).via(SENDER_TXN),
                cryptoCreate(RECEIVER),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .recordingScheduledTxn()
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TX),
                scheduleSign(BASIC_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            INSUFFICIENT_PAYER_BALANCE,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithDefaultPayerButAccountDeletedFails() {
        long balance = 10_000_000L;
        long noBalance = 0L;
        long transferAmount = 1L;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(LUCKY_RECEIVER),
                cryptoCreate(SENDER).balance(transferAmount).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 10)
                        .recordingScheduledTxn()
                        .payingWith(PAYING_ACCOUNT)
                        .via(CREATE_TX),
                recordFeeAmount(CREATE_TX, SCHEDULE_CREATE_FEE),
                cryptoDelete(PAYING_ACCOUNT),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 10)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER, 15),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                // future: a check if account was deleted will be added in DispatchValidator
                getTxnRecord(CREATE_TX)
                        .scheduled()
                        .hasPriority(recordWith().statusFrom(PAYER_ACCOUNT_DELETED, INSUFFICIENT_PAYER_BALANCE))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithCustomPayerButAccountDeletedFails() {
        long balance = 10_000_000L;
        long noBalance = 0L;
        long transferAmount = 1;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).balance(balance),
                cryptoCreate(SENDER).balance(transferAmount).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(BASIC_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 10)
                        .recordingScheduledTxn()
                        .designatingPayer(PAYING_ACCOUNT)
                        .alsoSigningWith(PAYING_ACCOUNT)
                        .via(CREATE_TX),
                cryptoDelete(PAYING_ACCOUNT),
                scheduleSign(BASIC_XFER).alsoSigningWith(SENDER).via(SIGN_TX).hasKnownStatus(SUCCESS),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                getScheduleInfo(BASIC_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 10)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(BASIC_XFER, 15),
                getAccountBalance(SENDER).hasTinyBars(transferAmount),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                // future: a check if account was deleted will be added in DispatchValidator
                getTxnRecord(CREATE_TX)
                        .scheduled()
                        .hasPriority(recordWith().statusFrom(INSUFFICIENT_PAYER_BALANCE, PAYER_ACCOUNT_DELETED))));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithCryptoInsufficientAccountBalanceFails() {
        long noBalance = 0L;
        long senderBalance = 100L;
        long transferAmount = 101L;
        long payerBalance = 1_000_000L;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).balance(payerBalance),
                cryptoCreate(SENDER).balance(senderBalance).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(FAILED_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 4)
                        .designatingPayer(PAYING_ACCOUNT)
                        .recordingScheduledTxn()
                        .via(CREATE_TX),
                scheduleSign(FAILED_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(FAILED_XFER)
                        .hasScheduleId(BASIC_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(FAILED_XFER),
                getAccountBalance(SENDER).hasTinyBars(senderBalance),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();

                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            INSUFFICIENT_ACCOUNT_BALANCE,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionWithCryptoSenderDeletedFails() {
        long noBalance = 0L;
        long senderBalance = 100L;
        long transferAmount = 101L;
        long payerBalance = 1_000_000L;
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).balance(payerBalance),
                cryptoCreate(SENDER).balance(senderBalance).via(SENDER_TXN),
                cryptoCreate(RECEIVER).balance(noBalance),
                scheduleCreate(FAILED_XFER, cryptoTransfer(tinyBarsFromTo(SENDER, RECEIVER, transferAmount)))
                        .waitForExpiry()
                        .withRelativeExpiry(SENDER_TXN, 5)
                        .recordingScheduledTxn()
                        .designatingPayer(PAYING_ACCOUNT)
                        .via(CREATE_TX),
                cryptoDelete(SENDER),
                scheduleSign(FAILED_XFER)
                        .alsoSigningWith(SENDER, PAYING_ACCOUNT)
                        .via(SIGN_TX)
                        .hasKnownStatus(SUCCESS),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                getScheduleInfo(FAILED_XFER)
                        .hasScheduleId(FAILED_XFER)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(SENDER_TXN, 5)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(FAILED_XFER),
                getAccountBalance(RECEIVER).hasTinyBars(noBalance),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(CREATE_TX).scheduled();
                    allRunFor(spec, triggeredTx);
                    Assertions.assertEquals(
                            ACCOUNT_DELETED,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    public Stream<DynamicTest> executionTriggersWithWeirdlyRepeatedKey() {
        String schedule = "dupKeyXfer";

        return hapiTest(flattened(
                cryptoCreate(WEIRDLY_POPULAR_KEY),
                cryptoCreate(SENDER_1).key(WEIRDLY_POPULAR_KEY).balance(1L),
                cryptoCreate(SENDER_2).key(WEIRDLY_POPULAR_KEY).balance(1L),
                cryptoCreate(SENDER_3).key(WEIRDLY_POPULAR_KEY).balance(1L),
                cryptoCreate(RECEIVER).balance(0L).via(WEIRDLY_POPULAR_KEY_TXN),
                scheduleCreate(
                                schedule,
                                cryptoTransfer(
                                        tinyBarsFromTo(SENDER_1, RECEIVER, 1L),
                                        tinyBarsFromTo(SENDER_2, RECEIVER, 1L),
                                        tinyBarsFromTo(SENDER_3, RECEIVER, 1L)))
                        .waitForExpiry()
                        .withRelativeExpiry(WEIRDLY_POPULAR_KEY_TXN, 4)
                        .payingWith(DEFAULT_PAYER)
                        .recordingScheduledTxn()
                        .via("creation"),
                scheduleSign(schedule).alsoSigningWith(WEIRDLY_POPULAR_KEY),
                getScheduleInfo(schedule)
                        .hasScheduleId(schedule)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(WEIRDLY_POPULAR_KEY_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(schedule),
                getAccountBalance(SENDER_1).hasTinyBars(0L),
                getAccountBalance(SENDER_2).hasTinyBars(0L),
                getAccountBalance(SENDER_3).hasTinyBars(0L),
                getAccountBalance(RECEIVER).hasTinyBars(3L),
                scheduleSign(schedule).alsoSigningWith(WEIRDLY_POPULAR_KEY).hasKnownStatus(INVALID_SCHEDULE_ID)));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> scheduledFreezeWorksAsExpected() {
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).via(PAYER_TXN),
                scheduleFakeUpgrade(PAYING_ACCOUNT, 4, SUCCESS_TXN),
                scheduleSign(VALID_SCHEDULE)
                        .alsoSigningWith(GENESIS)
                        .payingWith(PAYING_ACCOUNT)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(VALID_SCHEDULE)
                        .hasScheduleId(VALID_SCHEDULE)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRecordedScheduledTxn(),
                triggerSchedule(VALID_SCHEDULE),
                freezeAbort().payingWith(GENESIS),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, triggeredTx);
                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> scheduledPermissionedFileUpdateWorksAsExpected() {
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).via(PAYER_TXN),
                scheduleCreate(VALID_SCHEDULE, fileUpdate(standardUpdateFile).contents("fooo!"))
                        .withEntityMemo(randomUppercase(100))
                        .designatingPayer(SYSTEM_ADMIN)
                        .payingWith(PAYING_ACCOUNT)
                        .waitForExpiry()
                        .withRelativeExpiry(PAYER_TXN, 4)
                        .recordingScheduledTxn()
                        .via(SUCCESS_TXN),
                scheduleSign(VALID_SCHEDULE)
                        .alsoSigningWith(SYSTEM_ADMIN)
                        .payingWith(PAYING_ACCOUNT)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(VALID_SCHEDULE)
                        .hasScheduleId(VALID_SCHEDULE)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(PAYER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(VALID_SCHEDULE),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> scheduledPermissionedFileUpdateUnauthorizedPayerFails() {
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).via(PAYER_TXN),
                cryptoCreate(PAYING_ACCOUNT_2),
                scheduleCreate(VALID_SCHEDULE, fileUpdate(standardUpdateFile).contents("fooo!"))
                        .withEntityMemo(randomUppercase(100))
                        .designatingPayer(PAYING_ACCOUNT_2)
                        .payingWith(PAYING_ACCOUNT)
                        .waitForExpiry()
                        .withRelativeExpiry(PAYER_TXN, 4)
                        .recordingScheduledTxn()
                        .via(SUCCESS_TXN),
                scheduleSign(VALID_SCHEDULE)
                        .alsoSigningWith(PAYING_ACCOUNT_2, FREEZE_ADMIN)
                        .payingWith(PAYING_ACCOUNT)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(VALID_SCHEDULE)
                        .hasScheduleId(VALID_SCHEDULE)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(PAYER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(VALID_SCHEDULE),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            AUTHORIZATION_FAILED,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            "Scheduled transaction be AUTHORIZATION_FAILED!");
                })));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> scheduledSystemDeleteWorksAsExpected() {
        return hapiTest(flattened(
                cryptoCreate(PAYING_ACCOUNT).via(PAYER_TXN),
                fileCreate(FILE_NAME).lifetime(THREE_MONTHS_IN_SECONDS).contents(ORIG_FILE),
                scheduleCreate(VALID_SCHEDULE, systemFileDelete(FILE_NAME).updatingExpiry(1L))
                        .withEntityMemo(randomUppercase(100))
                        .designatingPayer(SYSTEM_DELETE_ADMIN)
                        .payingWith(PAYING_ACCOUNT)
                        .waitForExpiry()
                        .withRelativeExpiry(PAYER_TXN, 4)
                        .recordingScheduledTxn()
                        .via(SUCCESS_TXN),
                scheduleSign(VALID_SCHEDULE)
                        .alsoSigningWith(SYSTEM_DELETE_ADMIN)
                        .payingWith(PAYING_ACCOUNT)
                        .hasKnownStatus(SUCCESS),
                getScheduleInfo(VALID_SCHEDULE)
                        .hasScheduleId(VALID_SCHEDULE)
                        .hasWaitForExpiry()
                        .isNotExecuted()
                        .isNotDeleted()
                        .hasRelativeExpiry(PAYER_TXN, 4)
                        .hasRecordedScheduledTxn(),
                triggerSchedule(VALID_SCHEDULE),
                getFileInfo(FILE_NAME).nodePayment(1_234L).hasAnswerOnlyPrecheck(INVALID_FILE_ID),
                withOpContext((spec, opLog) -> {
                    var triggeredTx = getTxnRecord(SUCCESS_TXN).scheduled();
                    allRunFor(spec, triggeredTx);

                    Assertions.assertEquals(
                            SUCCESS,
                            triggeredTx.getResponseRecord().getReceipt().getStatus(),
                            SCHEDULED_TRANSACTION_MUST_NOT_SUCCEED);
                })));
    }
}
