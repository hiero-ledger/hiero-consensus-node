// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_STATE_ACCESS;
import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.EmbeddedVerbs.viewSingleton;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ADEQUATE_FUNDS;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATES;
import static com.hedera.services.bdd.suites.HapiSuite.EXCHANGE_RATE_CONTROL;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.contract.Utils.mirrorAddrWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.throttle.CongestionThrottleService;
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
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ThrottleDefinitions;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a system-file update executed by a child dispatch whose parent later rolls back does not change the
 * in-memory facility the file backs. A contract authorizes a scheduled exchange-rate update through the schedule
 * service, which executes the update as a child of the contract call, and then reverts. The active exchange rate must
 * still match the committed file.
 */
@Tag(INTEGRATION)
@HapiTestLifecycle
@TargetEmbeddedMode(REPEATABLE)
public class RevertedSystemFileUpdateTest {
    @Contract(contract = "RevertingScheduleAuthorizer", creationGas = 1_000_000)
    static SpecContract AUTHORIZER;

    private static final String AUTHORIZER_KEY = "revertingAuthorizerKey";
    private static final String RATE_UPDATE = "rateUpdate";

    private static final AtomicReference<byte[]> ORIGINAL_THROTTLES = new AtomicReference<>();

    @BeforeAll
    static void captureThrottles(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(getFileContents(THROTTLE_DEFS).consumedBy(ORIGINAL_THROTTLES::set));
    }

    @AfterAll
    static void restoreControlKeyAndThrottles(@NonNull final TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                sourcing(() -> fileUpdate(THROTTLE_DEFS).payingWith(GENESIS).contents(ORIGINAL_THROTTLES.get())));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> exchangeRateUpdateUnderRevertedContractCallLeavesActiveRateUnchanged() {
        final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();
        final AtomicReference<byte[]> fileBefore = new AtomicReference<>();
        final AtomicReference<byte[]> fileAfter = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateBefore = new AtomicReference<>();
        final AtomicReference<ExchangeRate> rateAfter = new AtomicReference<>();
        return hapiTest(
                AUTHORIZER.getInfo(),
                newKeyNamed(AUTHORIZER_KEY).shape(KeyShape.CONTRACT.signedWith(AUTHORIZER.name())),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // Only the contract can now complete a schedule paid by the exchange-rate control account
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(AUTHORIZER_KEY).payingWith(GENESIS),
                scheduleCreate(
                                RATE_UPDATE,
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> spec.ratesProvider()
                                                .rateSetWith(10, 121)
                                                .toByteString()))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS)
                        .exposingCreatedIdTo(scheduleId::set),
                getScheduleInfo(RATE_UPDATE).isNotExecuted(),
                getFileContents(EXCHANGE_RATES).consumedBy(fileBefore::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("before"),
                getTxnRecord("before")
                        .exposingTo(r ->
                                rateBefore.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                // The contract authorizes the schedule, which executes the update, and then reverts
                sourcingContextual(spec -> AUTHORIZER
                        .call(
                                "authorizeScheduleAndRevert",
                                mirrorAddrWith(spec, scheduleId.get().getScheduleNum()))
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))),
                getScheduleInfo(RATE_UPDATE).isNotExecuted(),
                getFileContents(EXCHANGE_RATES).consumedBy(fileAfter::set),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("after"),
                getTxnRecord("after")
                        .exposingTo(r ->
                                rateAfter.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                withOpContext((spec, log) -> {
                    assertArrayEquals(fileBefore.get(), fileAfter.get(), "file 0.0.112 unchanged");
                    assertEquals(rateBefore.get(), rateAfter.get(), "active rate must still match committed 0.0.112");
                }));
    }

    @RepeatableHapiTest(NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW)
    final Stream<DynamicTest> committedScheduledExchangeRateUpdateTakesEffect() {
        final AtomicReference<ExchangeRate> rateAfter = new AtomicReference<>();
        return hapiTest(
                // In case an earlier test in this class failed before handing the control key back
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                // Signed with the exchange-rate control key, so the schedule executes as a child of its creation
                scheduleCreate(
                                "committedRateUpdate",
                                fileUpdate(EXCHANGE_RATES)
                                        .contents(spec -> spec.ratesProvider()
                                                .rateSetWith(10, 121)
                                                .toByteString()))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS)
                        .alsoSigningWith(EXCHANGE_RATE_CONTROL),
                getScheduleInfo("committedRateUpdate").isExecuted(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).via("after"),
                getTxnRecord("after")
                        .exposingTo(r ->
                                rateAfter.set(r.getReceipt().getExchangeRate().getCurrentRate())),
                withOpContext((spec, log) -> {
                    assertEquals(10, rateAfter.get().getHbarEquiv(), "committed rate must be active");
                    assertEquals(121, rateAfter.get().getCentEquiv(), "committed rate must be active");
                }));
    }

    /**
     * A contract completes a scheduled throttle-definitions update (0.0.123) and reverts. The update rebuilt the
     * consensus throttle buckets with empty usage; once the file rolls back, the node must be running on the committed
     * definitions with the usage accumulated before the update, and that is what must be saved to state, not an
     * emptied bucket.
     */
    @LeakyRepeatableHapiTest(value = {NEEDS_SYNCHRONOUS_HANDLE_WORKFLOW, NEEDS_STATE_ACCESS})
    final Stream<DynamicTest> throttleDefinitionsUpdateUnderRevertedContractCallKeepsAccumulatedUsage() {
        final AtomicReference<ScheduleID> scheduleId = new AtomicReference<>();
        final AtomicReference<byte[]> defsBefore = new AtomicReference<>();
        final AtomicReference<byte[]> defsAfter = new AtomicReference<>();
        final AtomicReference<byte[]> defsCommitted = new AtomicReference<>();
        final AtomicLong usedBefore = new AtomicLong();
        final AtomicLong usedAfter = new AtomicLong();
        return hapiTest(
                AUTHORIZER.getInfo(),
                newKeyNamed(AUTHORIZER_KEY).shape(KeyShape.CONTRACT.signedWith(AUTHORIZER.name())),
                cryptoTransfer(tinyBarsFromTo(GENESIS, EXCHANGE_RATE_CONTROL, ADEQUATE_FUNDS))
                        .fee(ONE_HUNDRED_HBARS),
                cryptoCreate(CIVILIAN).balance(ONE_MILLION_HBARS),
                // Definitions with a bucket for every operation the test runs (so none is throttled at consensus); in
                // REPEATABLE mode consensus time barely advances between transactions, so the usage below does not
                // leak away before it is read back
                new SysFileOverrideOp(THROTTLES, () -> LONG_BURST_THROTTLES),
                getFileContents(THROTTLE_DEFS).consumedBy(defsBefore::set),
                blockingOrder(IntStream.range(0, 10)
                        .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(CIVILIAN, FUNDING, 1L))
                                .payingWith(CIVILIAN)
                                .noLogging())
                        .toArray(HapiSpecOperation[]::new)),
                viewSingleton(
                        CongestionThrottleService.NAME,
                        SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal(),
                        (ThrottleUsageSnapshots snapshots) -> usedBefore.set(snapshots
                                .tpsThrottles()
                                .get(cryptoTransferBucketIn(defsBefore.get()))
                                .used())),
                // Only the contract can now complete a schedule paid by the exchange-rate control account, which is
                // also privileged to update the throttle definitions
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(AUTHORIZER_KEY).payingWith(GENESIS),
                sourcingContextual(spec -> scheduleCreate(
                                THROTTLE_UPDATE,
                                fileUpdate(THROTTLE_DEFS).contents(withBurstSeconds(defsBefore.get(), 120)))
                        .designatingPayer(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS)
                        .exposingCreatedIdTo(scheduleId::set)),
                getScheduleInfo(THROTTLE_UPDATE).isNotExecuted(),
                // The contract authorizes the schedule, which executes the update and rebuilds the throttles, and then
                // reverts
                sourcingContextual(spec -> AUTHORIZER
                        .call(
                                "authorizeScheduleAndRevert",
                                mirrorAddrWith(spec, scheduleId.get().getScheduleNum()))
                        .gas(1_000_000L)
                        .andAssert(txn -> txn.hasKnownStatus(CONTRACT_REVERT_EXECUTED))
                        .via(REVERTING_CALL)),
                // The authorization did succeed inside the call before the revert (its child record is
                // REVERTED_SUCCESS,
                // not a failure status); the scheduled update's own record is discarded with the reverted frame, so
                // that it executed is shown by this plus the control at the end, where the same schedule executes and
                // takes effect once completed outside a reverting call. A failed authorization would also leave the
                // file and the usage unchanged, so the assertions below would pass vacuously without this
                getTxnRecord(REVERTING_CALL)
                        .andAllChildRecords()
                        .exposingAllTo(records -> assertTrue(
                                records.stream().anyMatch(r -> r.getReceipt().getStatus() == REVERTED_SUCCESS),
                                "the schedule authorization must have succeeded inside the reverted call; records: "
                                        + records.stream()
                                                .map(r -> r.getReceipt()
                                                        .getStatus()
                                                        .name())
                                                .toList())),
                getScheduleInfo(THROTTLE_UPDATE).isNotExecuted(),
                getFileContents(THROTTLE_DEFS).consumedBy(defsAfter::set),
                viewSingleton(
                        CongestionThrottleService.NAME,
                        SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal(),
                        (ThrottleUsageSnapshots snapshots) -> usedAfter.set(snapshots
                                .tpsThrottles()
                                .get(cryptoTransferBucketIn(defsBefore.get()))
                                .used())),
                cryptoUpdate(EXCHANGE_RATE_CONTROL).key(GENESIS).payingWith(GENESIS),
                // Control: the very same scheduled update executes and takes effect once completed outside a
                // reverting call, so the reverted attempt above did run a valid update
                scheduleSign(THROTTLE_UPDATE)
                        .alsoSigningWith(EXCHANGE_RATE_CONTROL)
                        .payingWith(GENESIS),
                getScheduleInfo(THROTTLE_UPDATE).isExecuted(),
                getFileContents(THROTTLE_DEFS).consumedBy(defsCommitted::set),
                withOpContext((spec, log) -> {
                    assertArrayEquals(
                            withBurstSeconds(defsBefore.get(), 120),
                            defsCommitted.get(),
                            "the committed schedule must have installed the updated definitions");
                    assertArrayEquals(defsBefore.get(), defsAfter.get(), "file 0.0.123 unchanged");
                    assertTrue(usedBefore.get() > 0, "the transfers must have accumulated usage before the update");
                    assertTrue(
                            usedAfter.get() >= usedBefore.get() / 2,
                            "the saved usage after the reverted update must still carry the accumulated history, was "
                                    + usedAfter.get() + " versus " + usedBefore.get() + " before");
                }));
    }

    private static final String CIVILIAN = "civilian";
    private static final String THROTTLE_UPDATE = "throttleUpdate";
    private static final String REVERTING_CALL = "revertingCall";
    private static final String LONG_BURST_THROTTLES = """
            {
              "buckets": [
                {
                  "name": "ThroughputLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    { "milliOpsPerSec": 10000, "operations": ["CryptoTransfer"] }
                  ]
                },
                {
                  "name": "AdminLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    {
                      "opsPerSec": 100,
                      "operations": [
                        "CryptoCreate", "CryptoUpdate", "FileUpdate", "ScheduleCreate", "ScheduleSign",
                        "ScheduleDelete", "ContractCall", "ContractCreate", "ContractUpdate"
                      ]
                    }
                  ]
                },
                {
                  "name": "QueryLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    {
                      "opsPerSec": 100,
                      "operations": [
                        "CryptoGetAccountBalance", "CryptoGetInfo", "ContractGetInfo", "ContractCallLocal",
                        "FileGetContents", "FileGetInfo", "ScheduleGetInfo", "TransactionGetRecord",
                        "TransactionGetReceipt"
                      ]
                    }
                  ]
                }
              ]
            }""";

    /** Returns the given throttle definitions with every bucket's burst period set to the given seconds. */
    private static byte[] withBurstSeconds(final byte[] encodedDefs, final int seconds) {
        try {
            final var defs = ThrottleDefinitions.parseFrom(encodedDefs);
            final var builder = defs.toBuilder();
            for (int i = 0; i < defs.getThrottleBucketsCount(); i++) {
                builder.setThrottleBuckets(
                        i, defs.getThrottleBuckets(i).toBuilder().setBurstPeriodMs(seconds * 1000L));
            }
            return builder.build().toByteArray();
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Returns the index of the bucket that throttles {@code CryptoTransfer} in the given definitions. */
    private static int cryptoTransferBucketIn(final byte[] encodedDefs) {
        try {
            final var defs = ThrottleDefinitions.parseFrom(encodedDefs);
            for (int i = 0; i < defs.getThrottleBucketsCount(); i++) {
                for (final var group : defs.getThrottleBuckets(i).getThrottleGroupsList()) {
                    if (group.getOperationsList().contains(HederaFunctionality.CryptoTransfer)) {
                        return i;
                    }
                }
            }
            throw new IllegalStateException("No bucket throttles CryptoTransfer");
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
