// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingAllOf;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * End-to-end regression covering the shared ops-duration throttle's isolation between callers.
 *
 * <p>One account calls {@code feedSaturatedModExp}, which invokes the MODEXP precompile (address
 * {@code 0x05}) with maximal length fields, so its reported gas requirement is far larger than the
 * transaction can afford. The inner call therefore halts for insufficient gas; the wrapper returns that
 * outcome as a boolean and the outer transaction still succeeds. This exercises the ops-duration
 * accounting path for a precompile call that ultimately does no work.
 *
 * <p>The test asserts that such a call consumes no shared ops-duration capacity: afterwards an unrelated
 * account's ordinary contract call still succeeds, and a non-contract transaction is likewise unaffected.
 * Ops duration is recorded only for calls that can afford their gas requirement, so a call that halts for
 * insufficient gas contributes nothing to the throttle.
 *
 * <p>Runs with the mainnet-profile throttle values (enabled, capacity 500M, leak 500M/s), which are also
 * the current {@code ContractsConfig} defaults.
 */
@Tag(SMART_CONTRACT)
public class OpsDurationPrecompileFeedTest {
    private static final String CONTRACT = "OpsDurationPrecompileFeed";
    private static final String MODEXP_CALLER = "modExpCaller";
    private static final String UNRELATED_CALLER = "unrelatedContractCaller";
    private static final String CONTROL_ACCOUNT = "nonContractControlAccount";

    private static final String BEFORE_TXN = "callBeforeFeed";
    private static final String FEED_TXN = "modExpFeed";
    private static final String AFTER_TXN = "callAfterFeed";
    private static final String CONTROL_TXN = "cryptoCreateAfterFeed";

    private static final String THROTTLE_BY_OPS_DURATION = "contracts.throttle.throttleByOpsDuration";
    private static final String THROTTLE_CAPACITY = "contracts.opsDurationThrottleCapacity";
    private static final String THROTTLE_UNITS_FREED_PER_SECOND = "contracts.opsDurationThrottleUnitsFreedPerSecond";

    private static final long MAINNET_CAPACITY = 500_000_000L;
    private static final long MAINNET_UNITS_FREED_PER_SECOND = 500_000_000L;

    @LeakyHapiTest(
            overrides = {
                "contracts.throttle.throttleByOpsDuration",
                "contracts.opsDurationThrottleCapacity",
                "contracts.opsDurationThrottleUnitsFreedPerSecond"
            })
    final Stream<DynamicTest> failedModExpCallDoesNotExhaustSharedThrottleForUnrelatedCaller() {
        return hapiTest(
                // Keep setup out of the throttle, then pin the mainnet-profile values.
                overriding(THROTTLE_BY_OPS_DURATION, "false"),
                cryptoCreate(MODEXP_CALLER).balance(100 * ONE_HBAR),
                cryptoCreate(UNRELATED_CALLER).balance(100 * ONE_HBAR),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT).gas(2_000_000L),
                overridingAllOf(Map.of(
                        THROTTLE_BY_OPS_DURATION, "true",
                        THROTTLE_CAPACITY, Long.toString(MAINNET_CAPACITY),
                        THROTTLE_UNITS_FREED_PER_SECOND, Long.toString(MAINNET_UNITS_FREED_PER_SECOND))),
                // The unrelated caller's ordinary call succeeds before the heavy MODEXP call runs.
                contractCall(CONTRACT, "readOne")
                        .payingWith(UNRELATED_CALLER)
                        .gas(200_000L)
                        .via(BEFORE_TXN)
                        .hasKnownStatus(SUCCESS),
                // Heavy MODEXP call: the inner precompile halts for insufficient gas, the outer tx succeeds.
                contractCall(CONTRACT, "feedSaturatedModExp")
                        .payingWith(MODEXP_CALLER)
                        .gas(14_000_000L)
                        .via(FEED_TXN)
                        .hasKnownStatus(SUCCESS)
                        .exposingResultTo(result -> assertEquals(
                                Boolean.FALSE, result[0], "inner MODEXP must halt while the outer call succeeds")),
                // The unrelated caller's ordinary call must still succeed: the halted MODEXP call recorded
                // no ops duration, so the shared throttle was not consumed.
                contractCall(CONTRACT, "readOne")
                        .payingWith(UNRELATED_CALLER)
                        .gas(200_000L)
                        .via(AFTER_TXN)
                        .hasKnownStatus(SUCCESS),
                // A non-contract HAPI transaction is likewise unaffected.
                cryptoCreate(CONTROL_ACCOUNT)
                        .payingWith(UNRELATED_CALLER)
                        .balance(ONE_HBAR)
                        .via(CONTROL_TXN)
                        .hasKnownStatus(SUCCESS),
                withOpContext((spec, opLog) -> {
                    final var before = getTxnRecord(BEFORE_TXN);
                    final var feed = getTxnRecord(FEED_TXN);
                    final var after = getTxnRecord(AFTER_TXN);
                    final var control = getTxnRecord(CONTROL_TXN);
                    allRunFor(spec, before, feed, after, control);

                    final var beforeStatus =
                            before.getResponseRecord().getReceipt().getStatus();
                    final var feedStatus = feed.getResponseRecord().getReceipt().getStatus();
                    final var afterStatus =
                            after.getResponseRecord().getReceipt().getStatus();
                    final var controlStatus =
                            control.getResponseRecord().getReceipt().getStatus();

                    assertEquals(SUCCESS, beforeStatus);
                    assertEquals(SUCCESS, feedStatus);
                    // The regression assertion: the unrelated caller is not throttled out.
                    assertEquals(SUCCESS, afterStatus, "unrelated contract call must remain unthrottled");
                    assertEquals(SUCCESS, controlStatus);

                    opLog.info(
                            "MODEXP_OPS_DURATION_REGRESSION callBefore={} feedStatus={} feedGasUsed={} "
                                    + "nextContractStatus={} nonContractControlStatus={} modExpCaller={} "
                                    + "unrelatedCaller={} capacity={} leakPerSecond={}",
                            beforeStatus,
                            feedStatus,
                            feed.getResponseRecord().getContractCallResult().getGasUsed(),
                            afterStatus,
                            controlStatus,
                            spec.registry().getAccountID(MODEXP_CALLER),
                            spec.registry().getAccountID(UNRELATED_CALLER),
                            MAINNET_CAPACITY,
                            MAINNET_UNITS_FREED_PER_SECOND);
                }));
    }
}
