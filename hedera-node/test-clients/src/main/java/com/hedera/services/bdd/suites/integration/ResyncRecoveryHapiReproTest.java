// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FEE_SCHEDULE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.HapiSuite.SYSTEM_ADMIN;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNPARSEABLE_THROTTLE_DEFINITIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ScheduleID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * HAPI-level reproductions of recovery gaps in the in-memory re-sync that runs after a dispatched system-file update
 * is rolled back (for example when a contract completes a scheduled system-file update and then reverts). When a
 * dispatch updates a system file in memory but its transaction does not commit, the in-memory facility must be
 * re-derived from committed state; these tests exercise the cases where that re-derivation was incomplete, so a node
 * would otherwise be left out of step with committed state. See {@code ResyncRecoveryReproTest} for the matching
 * unit-level reproductions against the real facility implementations. Each test restores every system file, key and
 * timing setting it changed before asserting, so a failing assertion does not leak state into later tests on the
 * shared embedded network.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class ResyncRecoveryHapiReproTest {
    @Contract(contract = "RevertingScheduleAuthorizer", creationGas = 1_000_000)
    static SpecContract AUTHORIZER;

    private static final String AUTHORIZER_KEY = "revertingAuthorizerKey";
    private static final int MIN_CONGESTION_PERIOD_SECS = 10;
    // 500 milliOps/sec with a 60s burst, so a transfer per second keeps utilization above 1% without throttling
    private static final String CONGESTION_THROTTLES = """
            {
              "buckets": [
                {
                  "name": "ThroughputLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    { "milliOpsPerSec": 500, "operations": ["CryptoTransfer"] }
                  ]
                },
                {
                  "name": "QueryLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    {
                      "opsPerSec": 100,
                      "operations": [
                        "CryptoGetAccountBalance", "FileGetContents", "FileGetInfo", "TransactionGetRecord",
                        "TransactionGetReceipt"
                      ]
                    }
                  ]
                }
              ]
            }""";
    // Submitting to a node other than the embedded node's own skips ingest, so only the consensus throttle applies
    private static final String NON_INGEST_NODE = "4";
    private static final String CIVILIAN = "civilian";
    private static final AtomicReference<byte[]> ORIGINAL_RATES = new AtomicReference<>();
    private static final AtomicReference<byte[]> ORIGINAL_FEE_SCHEDULE = new AtomicReference<>();

    @BeforeAll
    static void captureSystemFiles(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                getFileContents(EXCHANGE_RATES).consumedBy(ORIGINAL_RATES::set),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(ORIGINAL_FEE_SCHEDULE::set));
    }

    /**
     * Restores the keys and system files the tests change, even if a test fails before reaching its own cleanup.
     */
    @AfterAll
    static void restoreKeysAndSystemFiles(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                cryptoUpdate(FEE_SCHEDULE_CONTROL).key(GENESIS).payingWith(GENESIS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // Paid by the system admin so the midnight baseline is restored along with the rates
                sourcing(() -> fileUpdate(EXCHANGE_RATES)
                        .payingWith(SYSTEM_ADMIN)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(ORIGINAL_RATES.get())),
                sourcing(() -> fileUpdate(SIMPLE_FEE_SCHEDULE)
                        .payingWith(GENESIS)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(ORIGINAL_FEE_SCHEDULE.get())));
    }

    /**
     * Bug 1: a malformed 0.0.123 upload is registered for recovery before it is parsed. Parsing fails without
     * changing the throttles, the file update is rolled back, and recovery then rebuilds the unchanged definitions.
     * The bucket usage this wipes is reloaded from state by the next consensus-throttled dispatch, but the congestion
     * level start times are not, so congestion pricing restarts its minimum congestion period as if the network had
     * just become congested.
     */
    @LeakyRepeatableHapiTest(
            value = {MUST_SKIP_INGEST, NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    final Stream<DynamicTest> rejectedThrottleUploadRestartsCongestionPricing() {
        final AtomicLong normalFee = new AtomicLong();
        final AtomicLong congestedFee = new AtomicLong();
        final AtomicLong probeFee = new AtomicLong();
        final AtomicLong nextProbeFee = new AtomicLong();
        return hapiTest(
                cryptoCreate(CIVILIAN).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 5L))
                        .payingWith(CIVILIAN)
                        .setNode(NON_INGEST_NODE)
                        .via("normal"),
                getTxnRecord("normal").providingFeeTo(normalFee::set),
                overridingTwo(
                        "fees.percentCongestionMultipliers",
                        "1,7x",
                        "fees.minCongestionPeriod",
                        "" + MIN_CONGESTION_PERIOD_SECS),
                new SysFileOverrideOp(THROTTLES, () -> CONGESTION_THROTTLES),
                // Keep utilization above 1% for longer than the minimum congestion period
                blockingOrder(IntStream.range(0, 2 * MIN_CONGESTION_PERIOD_SECS)
                        .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 5L))
                                .payingWith(CIVILIAN)
                                .setNode(NON_INGEST_NODE)
                                .noLogging())
                        .toArray(HapiSpecOperation[]::new)),
                // Control: the congestion multiplier is in effect
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 5L))
                        .payingWith(CIVILIAN)
                        .setNode(NON_INGEST_NODE)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("congested"),
                getTxnRecord("congested").providingFeeTo(congestedFee::set),
                // A rejected upload must leave the throttles and their congestion history exactly as they were
                fileUpdate(THROTTLE_DEFS)
                        .payingWith(GENESIS)
                        .contents(new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF})
                        .hasKnownStatus(UNPARSEABLE_THROTTLE_DEFINITIONS),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 5L))
                        .payingWith(CIVILIAN)
                        .setNode(NON_INGEST_NODE)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("probe"),
                getTxnRecord("probe").providingFeeTo(probeFee::set),
                // The first transfer is priced with the multiplier cached before the upload; recomputing it after
                // that transfer is what exposes the restarted congestion period
                cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 5L))
                        .payingWith(CIVILIAN)
                        .setNode(NON_INGEST_NODE)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("nextProbe"),
                getTxnRecord("nextProbe").providingFeeTo(nextProbeFee::set),
                doingContextual(spec -> {
                    assertEquals(
                            7.0,
                            (1.0 * congestedFee.get()) / normalFee.get(),
                            0.1,
                            "the 7x congestion multiplier must be in effect before the upload");
                    assertEquals(
                            7.0,
                            (1.0 * probeFee.get()) / normalFee.get(),
                            0.1,
                            "the first transfer after the upload must still pay the congestion multiplier");
                    assertEquals(
                            7.0,
                            (1.0 * nextProbeFee.get()) / normalFee.get(),
                            0.1,
                            "a rejected throttle upload must not restart the minimum congestion period");
                }));
    }

    /**
     * Bug 2: an exchange-rate update whose transaction id names the system admin (0.0.50) moves the in-memory
     * midnight baseline even when the proposed bytes equal the current file. If a contract completes such a scheduled
     * update and reverts, the file is unchanged, so recovery is skipped and the moved baseline survives.
     *
     * <p>The payer named in a scheduled transaction's id is the schedule's creator, while its privileges are checked
     * against the schedule's designated payer. So 0.0.50 creates the schedule, and the designated payer 0.0.57 is
     * keyed to the contract so that only the contract can complete it.
     */
    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> revertedIdenticalRateUpdateLeaksMidnightBaseline() {
        final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();
        final AtomicReference<byte[]> originalRates = new AtomicReference<>();
        final AtomicReference<byte[]> committedRates = new AtomicReference<>();
        final AtomicReference<ResponseCodeEnum> probeStatus = new AtomicReference<>();
        return hapiTest(
                AUTHORIZER.getInfo(),
                newKeyNamed(AUTHORIZER_KEY).shape(KeyShape.CONTRACT.signedWith(AUTHORIZER.name())),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, SYSTEM_ADMIN, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                getFileContents(EXCHANGE_RATES).consumedBy(originalRates::set),
                // The system admin moves the midnight baseline to 1:100
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(SYSTEM_ADMIN)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 100).toByteString()),
                // The rates admin moves the current rate to 1:120, within 25% of the baseline
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 120).toByteString()),
                getFileContents(EXCHANGE_RATES).consumedBy(committedRates::set),
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(AUTHORIZER_KEY).payingWith(GENESIS),
                // Re-propose the identical committed bytes, created by the system admin
                sourcingContextual(spec -> scheduleCreate(
                                "rateUpdate",
                                fileUpdate(EXCHANGE_RATES).contents(ByteString.copyFrom(committedRates.get())))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(SYSTEM_ADMIN)
                        .fee(ONE_HUNDRED_HBARS)
                        .exposingCreatedIdTo(scheduleId::set)),
                getScheduleInfo("rateUpdate").isNotExecuted(),
                sourcingContextual(spec -> AUTHORIZER
                        .call(
                                "authorizeScheduleAndRevert",
                                mirrorAddrWith(spec, scheduleId.get().getScheduleNum()))
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getScheduleInfo("rateUpdate").isNotExecuted(),
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                // 1:144 is 20% above the leaked 1:120 baseline, but 44% above the committed 1:100 baseline
                fileUpdate(EXCHANGE_RATES)
                        .payingWith(EXCHANGE_RATE_CONTROL)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(
                                spec -> spec.ratesProvider().rateSetWith(1, 144).toByteString())
                        .via("probe")
                        .hasKnownStatusFrom(SUCCESS, EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED),
                getTxnRecord("probe")
                        .exposingTo(r -> probeStatus.set(r.getReceipt().getStatus())),
                sourcingContextual(spec -> fileUpdate(EXCHANGE_RATES)
                        .payingWith(SYSTEM_ADMIN)
                        .fee(ONE_HUNDRED_HBARS)
                        .contents(originalRates.get())),
                withOpContext((spec, log) -> assertEquals(
                        EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED,
                        probeStatus.get(),
                        "the intraday limit must still apply against the committed midnight baseline")));
    }

    /**
     * Bug 3: the committed simple fee schedule (0.0.113) can legitimately hold a partial upload while
     * {@code FeeManager} keeps using the last valid schedule. If a contract completes a scheduled update that
     * installs a different valid schedule and then reverts, the file rolls back to the partial bytes; recovery cannot
     * parse those and leaves the wrongly-installed schedule active.
     */
    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> revertedScheduleOverPartialFeeScheduleLeavesWrongScheduleActive() {
        final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();
        final AtomicReference<byte[]> originalScheduleBytes = new AtomicReference<>();
        final AtomicReference<Long> feeBefore = new AtomicReference<>();
        final AtomicReference<Long> feeAfter = new AtomicReference<>();
        return hapiTest(
                AUTHORIZER.getInfo(),
                newKeyNamed(AUTHORIZER_KEY).shape(KeyShape.CONTRACT.signedWith(AUTHORIZER.name())),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FEE_SCHEDULE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoCreate("measurer").balance(ONE_HUNDRED_HBARS),
                getFileContents(SIMPLE_FEE_SCHEDULE).consumedBy(originalScheduleBytes::set),
                cryptoTransfer(tinyBarsFromTo("measurer", FUNDING, 1))
                        .payingWith("measurer")
                        .fee(ONE_HUNDRED_HBARS)
                        .via("before"),
                getTxnRecord("before").exposingTo(r -> feeBefore.set(r.getTransactionFee())),
                // Commit a partial upload: FeeManager keeps the previously active schedule
                withOpContext((spec, log) -> {
                    final var full = originalScheduleBytes.get();
                    final var partial = new byte[full.length / 2];
                    System.arraycopy(full, 0, partial, 0, partial.length);
                    allRunFor(
                            spec,
                            fileUpdate(SIMPLE_FEE_SCHEDULE)
                                    .payingWith(FEE_SCHEDULE_CONTROL)
                                    .contents(partial)
                                    .fee(ONE_HUNDRED_HBARS)
                                    .hasKnownStatus(FEE_SCHEDULE_FILE_PART_UPLOADED));
                }),
                cryptoUpdate(FEE_SCHEDULE_CONTROL).key(AUTHORIZER_KEY).payingWith(GENESIS),
                sourcingContextual(spec -> scheduleCreate(
                                "feeScheduleUpdate",
                                fileUpdate(SIMPLE_FEE_SCHEDULE)
                                        .contents(
                                                ByteString.copyFrom(scaledSchedule(originalScheduleBytes.get(), 100))))
                        .designatingPayer(FEE_SCHEDULE_CONTROL)
                        .payingWith(GENESIS)
                        .exposingCreatedIdTo(scheduleId::set)),
                getScheduleInfo("feeScheduleUpdate").isNotExecuted(),
                // The schedule installs the scaled schedule, then the contract reverts and the file rolls back to
                // the partial bytes
                sourcingContextual(spec -> AUTHORIZER
                        .call(
                                "authorizeScheduleAndRevert",
                                mirrorAddrWith(spec, scheduleId.get().getScheduleNum()))
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getScheduleInfo("feeScheduleUpdate").isNotExecuted(),
                cryptoTransfer(tinyBarsFromTo("measurer", FUNDING, 1))
                        .payingWith("measurer")
                        .fee(ONE_HUNDRED_HBARS)
                        .via("after"),
                getTxnRecord("after").exposingTo(r -> feeAfter.set(r.getTransactionFee())),
                cryptoUpdate(FEE_SCHEDULE_CONTROL).key(GENESIS).payingWith(GENESIS),
                sourcingContextual(spec -> fileUpdate(SIMPLE_FEE_SCHEDULE)
                        .payingWith(FEE_SCHEDULE_CONTROL)
                        .contents(originalScheduleBytes.get())
                        .fee(ONE_HUNDRED_HBARS)),
                withOpContext((spec, log) -> assertEquals(
                        feeBefore.get(),
                        feeAfter.get(),
                        "fee must still follow the schedule active before the reverted update, not the "
                                + "wrongly-installed one")));
    }

    private static byte[] scaledSchedule(final byte[] original, final long scale) {
        try {
            final var s = FeeSchedule.PROTOBUF.parse(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(original));
            final var raised = s.copyBuilder()
                    .node(s.nodeOrThrow()
                            .copyBuilder()
                            .baseFee(s.nodeOrThrow().baseFee() * scale)
                            .build())
                    .extras(s.extras().stream()
                            .map(e -> e.copyBuilder().fee(e.fee() * scale).build())
                            .toList())
                    .services(s.services().stream()
                            .map(sv -> sv.copyBuilder()
                                    .schedule(sv.schedule().stream()
                                            .map(d -> d.copyBuilder()
                                                    .baseFee(d.baseFee() * scale)
                                                    .build())
                                            .toList())
                                    .build())
                            .toList())
                    .build();
            return FeeSchedule.PROTOBUF.toBytes(raised).toByteArray();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
