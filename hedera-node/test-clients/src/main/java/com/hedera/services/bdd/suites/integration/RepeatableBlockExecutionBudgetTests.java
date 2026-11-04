// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.createHollow;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData.EthTransactionType;
import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.restart.RestartHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// Private embedded networks must finish before any shared embedded network starts.
@Order(Integer.MIN_VALUE)
@Tag(INTEGRATION)
public class RepeatableBlockExecutionBudgetTests {
    private static final String PAYER = "budgetPayer";
    private static final String RECEIVER = "budgetReceiver";
    private static final String BATCH_OPERATOR = "budgetBatchOperator";
    private static final String HOLLOW = "budgetHollow";
    private static final Duration BLOCK_PERIOD = Duration.ofSeconds(60);

    // A private repeatable network ensures BLOCKS is selected before workflow construction.
    // Leave enforcement disabled for genesis and setup, then enable it through file 0.0.121.
    @RestartHapiTest(
            restartOverrides = {
                @ConfigOverride(key = "blockStream.streamMode", value = "BLOCKS"),
                @ConfigOverride(key = "blockStream.blockPeriod", value = "60s"),
                @ConfigOverride(key = "blockStream.maxBlockExecutionSizeBytes", value = "16384"),
                @ConfigOverride(key = "blockStream.maxBlockSizeLimitEnabled", value = "false")
            })
    Stream<DynamicTest> crossingTransactionSucceedsAndLaterSubmissionsPayOnlyThrottleFees() {
        final var balanceBefore = new AtomicLong();
        final var chargedFee = new AtomicLong();
        final var receiverBefore = new AtomicLong();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> {
                    openNextBlock(spec);
                    fillBlock(spec);
                }),
                viewAccount(PAYER, account -> balanceBefore.set(account.tinybarBalance())),
                viewAccount(RECEIVER, account -> receiverBefore.set(account.tinybarBalance())),
                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                        .payingWith(PAYER)
                        .via("rejectedForBudget")
                        .hasKnownStatus(THROTTLED_AT_CONSENSUS),
                getTxnRecord("rejectedForBudget")
                        .hasPriority(recordWith().status(THROTTLED_AT_CONSENSUS))
                        .exposingTo(record -> {
                            assertTrue(record.getTransactionFee() > 0);
                            chargedFee.set(record.getTransactionFee());
                        }),
                validateChargedUsdWithinWithTxnSize(
                        "rejectedForBudget",
                        size -> expectedNetworkOnlyFeeUsd(
                                        Map.of(Extra.SIGNATURES, 1L, Extra.PROCESSING_BYTES, (long) size))
                                * (NETWORK_MULTIPLIER + 1.0)
                                / NETWORK_MULTIPLIER,
                        0.1),
                viewAccount(
                        PAYER,
                        account -> assertEquals(balanceBefore.get() - chargedFee.get(), account.tinybarBalance())),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get(), account.tinybarBalance())),
                cryptoTransfer(tinyBarsFromTo(GENESIS, RECEIVER, 1))
                        .payingWith(GENESIS)
                        .via("privilegedAfterBudget")
                        .hasKnownStatus(SUCCESS),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get() + 1, account.tinybarBalance())),
                withOpContext((spec, log) -> assertTrue(spec.embeddedHederaOrThrow()
                        .hedera()
                        .blockStreamManager()
                        .hasReachedMaxBlockSize())));
    }

    @RestartHapiTest(
            restartOverrides = {
                @ConfigOverride(key = "blockStream.streamMode", value = "BLOCKS"),
                @ConfigOverride(key = "blockStream.blockPeriod", value = "60s"),
                @ConfigOverride(key = "blockStream.maxBlockExecutionSizeBytes", value = "16384"),
                @ConfigOverride(key = "blockStream.maxBlockSizeLimitEnabled", value = "false"),
                @ConfigOverride(key = "scheduling.longTermEnabled", value = "true")
            })
    Stream<DynamicTest> dueScheduleExecutesInFullBlock() {
        final var receiverBefore = new AtomicLong();
        final var fullBlock = new AtomicLong();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> openNextBlock(spec)),
                // Paid by an account outside the throttle-exempt range
                scheduleCreate("exemptSchedule", cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 7)))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(PAYER)
                        .waitForExpiry()
                        .expiringIn(2)
                        .via("scheduleCreation"),
                withOpContext((spec, log) -> {
                    fillBlock(spec);
                    fullBlock.set(spec.embeddedHederaOrThrow()
                            .hedera()
                            .blockStreamManager()
                            .blockNo());
                }),
                viewAccount(RECEIVER, account -> receiverBefore.set(account.tinybarBalance())),
                withOpContext((spec, log) -> {
                    spec.embeddedHederaOrThrow().tick(Duration.ofSeconds(3));
                    // The triggering submission is rejected, but the due schedule still executes after it
                    allRunFor(
                            spec,
                            cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                                    .payingWith(PAYER)
                                    .hasKnownStatus(THROTTLED_AT_CONSENSUS));
                }),
                // Executed expiry schedules are purged; the scheduled record and balance prove execution.
                getScheduleInfo("exemptSchedule").hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                getTxnRecord("scheduleCreation")
                        .scheduled()
                        .hasPriority(recordWith().status(SUCCESS)),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get() + 7, account.tinybarBalance())),
                withOpContext((spec, log) -> assertStillInFullBlock(spec, fullBlock.get())));
    }

    @RestartHapiTest(
            restartOverrides = {
                @ConfigOverride(key = "blockStream.streamMode", value = "BLOCKS"),
                @ConfigOverride(key = "blockStream.blockPeriod", value = "60s"),
                @ConfigOverride(key = "blockStream.maxBlockExecutionSizeBytes", value = "16384"),
                @ConfigOverride(key = "blockStream.maxBlockSizeLimitEnabled", value = "false")
            })
    Stream<DynamicTest> ethereumTransactionInFullBlockIsRejectedWithoutConsumingNonce() {
        final var receiverBefore = new AtomicLong();
        final var relayerBefore = new AtomicLong();
        return hapiTest(
                newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                doingContextual(spec -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> {
                    openNextBlock(spec);
                    fillBlock(spec);
                }),
                viewAccount(RECEIVER, account -> receiverBefore.set(account.tinybarBalance())),
                viewAccount(RELAYER, account -> relayerBefore.set(account.tinybarBalance())),
                ethereumCryptoTransfer(RECEIVER, FIVE_HBARS)
                        .type(EthTransactionType.EIP1559)
                        .signingWith(SECP_256K1_SOURCE_KEY)
                        .payingWith(RELAYER)
                        .nonce(0)
                        .maxFeePerGas(0L)
                        .maxGasAllowance(FIVE_HBARS)
                        .gasLimit(2_000_000L)
                        .via("ethRejected")
                        .hasKnownStatus(THROTTLED_AT_CONSENSUS),
                // The record still identifies the Ethereum transaction, so its hash can be looked up
                getTxnRecord("ethRejected")
                        .hasPriority(recordWith().status(THROTTLED_AT_CONSENSUS))
                        .exposingTo(
                                record -> assertFalse(record.getEthereumHash().isEmpty())),
                // The sender can resubmit with the same nonce
                getAliasedAccountInfo(SECP_256K1_SOURCE_KEY).has(accountWith().nonce(0L)),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get(), account.tinybarBalance())),
                viewAccount(RELAYER, account -> assertTrue(account.tinybarBalance() < relayerBefore.get())));
    }

    @RestartHapiTest(
            restartOverrides = {
                @ConfigOverride(key = "blockStream.streamMode", value = "BLOCKS"),
                @ConfigOverride(key = "blockStream.blockPeriod", value = "60s"),
                @ConfigOverride(key = "blockStream.maxBlockExecutionSizeBytes", value = "16384"),
                @ConfigOverride(key = "blockStream.maxBlockSizeLimitEnabled", value = "false")
            })
    Stream<DynamicTest> atomicBatchInFullBlockIsRejectedWithoutExecutingInnerTransactions() {
        final var payerBefore = new AtomicLong();
        final var receiverBefore = new AtomicLong();
        final var operatorBefore = new AtomicLong();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> {
                    openNextBlock(spec);
                    fillBlock(spec);
                }),
                viewAccount(PAYER, account -> payerBefore.set(account.tinybarBalance())),
                viewAccount(RECEIVER, account -> receiverBefore.set(account.tinybarBalance())),
                viewAccount(BATCH_OPERATOR, account -> operatorBefore.set(account.tinybarBalance())),
                atomicBatch(cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 5))
                                .payingWith(PAYER)
                                .batchKey(BATCH_OPERATOR))
                        .payingWith(BATCH_OPERATOR)
                        .via("batchRejected")
                        .hasKnownStatus(THROTTLED_AT_CONSENSUS),
                getTxnRecord("batchRejected").hasPriority(recordWith().status(THROTTLED_AT_CONSENSUS)),
                // Only the batch payer is charged; the inner transfer never runs or charges its own payer
                viewAccount(BATCH_OPERATOR, account -> assertTrue(account.tinybarBalance() < operatorBefore.get())),
                viewAccount(PAYER, account -> assertEquals(payerBefore.get(), account.tinybarBalance())),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get(), account.tinybarBalance())));
    }

    @RestartHapiTest(
            restartOverrides = {
                @ConfigOverride(key = "blockStream.streamMode", value = "BLOCKS"),
                @ConfigOverride(key = "blockStream.blockPeriod", value = "60s"),
                @ConfigOverride(key = "blockStream.maxBlockExecutionSizeBytes", value = "16384"),
                @ConfigOverride(key = "blockStream.maxBlockSizeLimitEnabled", value = "false")
            })
    Stream<DynamicTest> hollowPayerInFullBlockIsFinalizedButItsTransactionRejected() {
        final var receiverBefore = new AtomicLong();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                createHollow(1, i -> HOLLOW),
                getAccountInfo(HOLLOW).has(accountWith().hasEmptyKey()),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> {
                    openNextBlock(spec);
                    fillBlock(spec);
                }),
                viewAccount(RECEIVER, account -> receiverBefore.set(account.tinybarBalance())),
                cryptoTransfer(tinyBarsFromTo(HOLLOW, RECEIVER, 1))
                        .payingWith(HOLLOW)
                        .signedBy(HOLLOW)
                        .sigMapPrefixes(uniqueWithFullPrefixesFor(HOLLOW))
                        .via("hollowRejected")
                        .hasKnownStatus(THROTTLED_AT_CONSENSUS),
                // As with any consensus throttling, finalizing the hollow payer survives the rollback
                getAccountInfo(HOLLOW).has(accountWith().hasNonEmptyKey()),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get(), account.tinybarBalance())));
    }

    @RestartHapiTest(
            restartOverrides = {
                @ConfigOverride(key = "blockStream.streamMode", value = "BLOCKS"),
                @ConfigOverride(key = "blockStream.blockPeriod", value = "60s"),
                @ConfigOverride(key = "blockStream.maxBlockExecutionSizeBytes", value = "16384"),
                @ConfigOverride(key = "blockStream.maxBlockSizeLimitEnabled", value = "false"),
                @ConfigOverride(key = "scheduling.longTermEnabled", value = "true"),
                @ConfigOverride(key = "scheduling.maxExecutionsPerUserTxn", value = "2")
            })
    Stream<DynamicTest> scheduleBacklogDrainsInFullBlockWithinExecutionLimit() {
        final var receiverBefore = new AtomicLong();
        final var fullBlock = new AtomicLong();
        final List<Instant> executionTimes = new ArrayList<>();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> openNextBlock(spec)),
                scheduleCreate("firstSchedule", cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1)))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(PAYER)
                        .waitForExpiry()
                        .expiringIn(2)
                        .via("firstCreation"),
                scheduleCreate("secondSchedule", cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 10)))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(PAYER)
                        .waitForExpiry()
                        .expiringIn(2)
                        .via("secondCreation"),
                scheduleCreate("thirdSchedule", cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 100)))
                        .designatingPayer(PAYER)
                        .alsoSigningWith(PAYER)
                        .waitForExpiry()
                        .expiringIn(2)
                        .via("thirdCreation"),
                withOpContext((spec, log) -> {
                    fillBlock(spec);
                    fullBlock.set(spec.embeddedHederaOrThrow()
                            .hedera()
                            .blockStreamManager()
                            .blockNo());
                }),
                viewAccount(RECEIVER, account -> receiverBefore.set(account.tinybarBalance())),
                withOpContext((spec, log) -> {
                    spec.embeddedHederaOrThrow().tick(Duration.ofSeconds(3));
                    allRunFor(
                            spec,
                            cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                                    .payingWith(PAYER)
                                    .hasKnownStatus(THROTTLED_AT_CONSENSUS));
                }),
                // The exemption does not lift the normal limit of maxExecutionsPerUserTxn=2 per submission
                getScheduleInfo("firstSchedule").hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                getScheduleInfo("secondSchedule").hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                getScheduleInfo("thirdSchedule").isNotExecuted(),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get() + 11, account.tinybarBalance())),
                cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                        .payingWith(PAYER)
                        .hasKnownStatus(THROTTLED_AT_CONSENSUS),
                getScheduleInfo("thirdSchedule").hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get() + 111, account.tinybarBalance())),
                withOpContext((spec, log) -> assertStillInFullBlock(spec, fullBlock.get())),
                // The backlog executes in creation (expiry) order
                withOpContext((spec, log) -> {
                    for (final var creation : List.of("firstCreation", "secondCreation", "thirdCreation")) {
                        allRunFor(
                                spec,
                                getTxnRecord(creation)
                                        .scheduled()
                                        .hasPriority(recordWith().status(SUCCESS))
                                        .exposingTo(record -> executionTimes.add(Instant.ofEpochSecond(
                                                record.getConsensusTimestamp().getSeconds(),
                                                record.getConsensusTimestamp().getNanos()))));
                    }
                    assertTrue(executionTimes.get(0).isBefore(executionTimes.get(1)));
                    assertTrue(executionTimes.get(1).isBefore(executionTimes.get(2)));
                }));
    }

    private static void assertStillInFullBlock(final HapiSpec spec, final long fullBlock) {
        final var manager = spec.embeddedHederaOrThrow().hedera().blockStreamManager();
        assertEquals(fullBlock, manager.blockNo(), "Scheduled executions must run in the same full block");
        assertTrue(manager.hasReachedMaxBlockSize());
    }

    private static void openNextBlock(final HapiSpec spec) {
        final var embedded = spec.repeatableEmbeddedHederaOrThrow();
        embedded.setRoundDuration(Duration.ofMillis(1));
        embedded.tick(BLOCK_PERIOD.plusSeconds(1));
        // The first round closes the old block; the second opens the next one.
        allRunFor(
                spec,
                cryptoTransfer(tinyBarsFromTo(GENESIS, "98", 1)).payingWith(GENESIS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, "98", 1)).payingWith(GENESIS));
        assertFalse(embedded.hedera().blockStreamManager().hasReachedMaxBlockSize());
    }

    private static void fillBlock(final HapiSpec spec) {
        final var manager = spec.embeddedHederaOrThrow().hedera().blockStreamManager();
        assertFalse(manager.hasReachedMaxBlockSize());
        final long blockNo = manager.blockNo();
        int count = 0;
        while (!manager.hasReachedMaxBlockSize() && count < 200) {
            // Every transfer handled before the budget is reached succeeds, including one that crosses it. But the
            // round and event headers written just before a transfer can cross it instead, and then that transfer
            // is rejected; it is necessarily the last one, since the loop then sees the budget reached.
            allRunFor(
                    spec,
                    cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                            .payingWith(PAYER)
                            .hasKnownStatusFrom(SUCCESS, THROTTLED_AT_CONSENSUS));
            count++;
        }
        assertTrue(manager.hasReachedMaxBlockSize(), "Transfers must fill the configured small block budget");
        assertEquals(blockNo, manager.blockNo(), "All transfers must remain in the same block");
    }
}
