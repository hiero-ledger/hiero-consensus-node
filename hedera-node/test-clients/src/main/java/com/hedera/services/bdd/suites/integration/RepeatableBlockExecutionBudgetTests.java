// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NETWORK_MULTIPLIER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.ConfigOverride;
import com.hedera.services.bdd.junit.restart.RestartHapiTest;
import com.hedera.services.bdd.spec.HapiSpec;
import java.time.Duration;
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
    Stream<DynamicTest> dueScheduleRemainsPendingInFullBlockAndExecutesInNextBlock() {
        final var receiverBefore = new AtomicLong();
        final var fullBlock = new AtomicLong();
        return hapiTest(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                overriding("blockStream.maxBlockSizeLimitEnabled", "true"),
                withOpContext((spec, log) -> openNextBlock(spec)),
                scheduleCreate("deferredSchedule", cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 7)))
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
                    allRunFor(
                            spec,
                            cryptoTransfer(tinyBarsFromTo(GENESIS, "98", 1)).payingWith(GENESIS));
                    assertEquals(
                            fullBlock.get(),
                            spec.embeddedHederaOrThrow()
                                    .hedera()
                                    .blockStreamManager()
                                    .blockNo());
                }),
                getScheduleInfo("deferredSchedule").isNotExecuted(),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get(), account.tinybarBalance())),
                withOpContext((spec, log) -> {
                    openNextBlock(spec);
                    assertEquals(
                            fullBlock.get() + 1,
                            spec.embeddedHederaOrThrow()
                                    .hedera()
                                    .blockStreamManager()
                                    .blockNo());
                }),
                // Executed expiry schedules are purged; the scheduled record and balance prove execution.
                getScheduleInfo("deferredSchedule").hasCostAnswerPrecheck(INVALID_SCHEDULE_ID),
                getTxnRecord("scheduleCreation")
                        .scheduled()
                        .hasPriority(recordWith().status(SUCCESS)),
                viewAccount(RECEIVER, account -> assertEquals(receiverBefore.get() + 7, account.tinybarBalance())));
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
            // Every transfer, including the one that crosses the budget, must succeed.
            allRunFor(
                    spec,
                    cryptoTransfer(tinyBarsFromTo(PAYER, RECEIVER, 1))
                            .payingWith(PAYER)
                            .hasKnownStatus(SUCCESS));
            count++;
        }
        assertTrue(manager.hasReachedMaxBlockSize(), "Transfers must fill the configured small block budget");
        assertEquals(blockNo, manager.blockNo(), "All transfers must remain in the same block");
    }
}
