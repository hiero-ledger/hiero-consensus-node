// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Regression tests that a top-level {@code CONTRACT_CALL} is charged its own consensus-throttle capacity even when
 * it dispatches a native child, using a shared throttle bucket close to the mainnet layout.
 *
 * <p>{@code shared-contract-call-child-throttle.json} places {@code ContractCall} (1 ops/s) and the cheap children
 * {@code UtilPrng}/{@code CryptoTransfer} (30 ops/s) in one {@code ThroughputLimits} bucket (a 30:1 cost ratio, as
 * in the mainnet layout), sized so exactly one contract-call-plus-child fits and a second does not.
 *
 * <p>Both tests exercise only the backend consensus throttle: the burst is paid by a freshly created civilian
 * (entity num &gt; {@code accounts.lastThrottleExempt} = 100, so it is not throttle-exempt) and submitted to a
 * non-default node ({@code 0.0.4}) via {@code setNode("4")} so the per-node ingest checks are skipped; setup runs
 * on the default node with the throttle-exempt payer. {@code contracts.throttle.throttleByGas} is left at its
 * default {@code false} so a throttled contract call resolves to {@code THROTTLED_AT_CONSENSUS} (not
 * {@code CONSENSUS_GAS_EXHAUSTED}). Both calls use {@code deferStatusResolution()} so they land in the same round
 * before either resolves (the 1-op {@code ContractCall} group would otherwise leak back).
 */
public class ContractCallParentThrottleUnitTest {
    private static final String CIVILIAN = "civilian";
    private static final String PRNG_CONTRACT = "PrngSystemContract";
    private static final String STORAGE_CONTRACT = "Storage";
    private static final String THROTTLES = "testSystemFiles/shared-contract-call-child-throttle.json";

    /**
     * A contract call that dispatches exactly one cheap {@code UtilPrng} child. The parent {@code CONTRACT_CALL}
     * unit is counted, so the second call is {@code THROTTLED_AT_CONSENSUS}, just like a childless call.
     */
    @LeakyEmbeddedHapiTest(
            reason = {MUST_SKIP_INGEST},
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.throttle.throttleByGas"},
            throttles = THROTTLES)
    final Stream<DynamicTest> parentUnitIsCountedWhenCallDispatchesCheapChild() {
        final Map<String, ResponseCodeEnum> seen = new LinkedHashMap<>();
        return hapiTest(
                overriding("contracts.throttle.throttleByGas", "false"),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(PRNG_CONTRACT),
                contractCreate(PRNG_CONTRACT).gas(2_000_000L),
                // Recreate all buckets with zero usage immediately before the burst.
                overridingThrottles(THROTTLES),
                contractCall(PRNG_CONTRACT, "getPseudorandomSeed")
                        .gas(2_000_000L)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .setNode("4")
                        .deferStatusResolution()
                        .via("prng0"),
                contractCall(PRNG_CONTRACT, "getPseudorandomSeed")
                        .gas(2_000_000L)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .setNode("4")
                        .deferStatusResolution()
                        .via("prng1"),
                getTxnRecord("prng0")
                        .exposingTo(r -> seen.put("prng0", r.getReceipt().getStatus())),
                getTxnRecord("prng1")
                        .exposingTo(r -> seen.put("prng1", r.getReceipt().getStatus())),
                doingContextual(spec -> {
                    assertEquals(SUCCESS, seen.get("prng0"), "first contract call (with a cheap child) should succeed");
                    assertEquals(
                            THROTTLED_AT_CONSENSUS,
                            seen.get("prng1"),
                            "second contract call must be throttled: the parent CONTRACT_CALL unit is counted "
                                    + "even when the call dispatches a native child");
                }));
    }

    /**
     * Control: two childless contract calls ({@code Storage.store}) against the same bucket, confirming the bucket
     * enforces the {@code CONTRACT_CALL} limit regardless of whether a child is dispatched.
     */
    @LeakyEmbeddedHapiTest(
            reason = {MUST_SKIP_INGEST},
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.throttle.throttleByGas"},
            throttles = THROTTLES)
    final Stream<DynamicTest> childlessCallIsThrottledOnSecond() {
        final Map<String, ResponseCodeEnum> seen = new LinkedHashMap<>();
        return hapiTest(
                overriding("contracts.throttle.throttleByGas", "false"),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(STORAGE_CONTRACT),
                contractCreate(STORAGE_CONTRACT).gas(2_000_000L),
                overridingThrottles(THROTTLES),
                contractCall(STORAGE_CONTRACT, "store", BigInteger.valueOf(1L))
                        .gas(500_000L)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .setNode("4")
                        .deferStatusResolution()
                        .via("store0"),
                contractCall(STORAGE_CONTRACT, "store", BigInteger.valueOf(2L))
                        .gas(500_000L)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .setNode("4")
                        .deferStatusResolution()
                        .via("store1"),
                getTxnRecord("store0")
                        .exposingTo(r -> seen.put("store0", r.getReceipt().getStatus())),
                getTxnRecord("store1")
                        .exposingTo(r -> seen.put("store1", r.getReceipt().getStatus())),
                doingContextual(spec -> {
                    assertEquals(SUCCESS, seen.get("store0"), "first childless contract call should succeed");
                    assertEquals(
                            THROTTLED_AT_CONSENSUS,
                            seen.get("store1"),
                            "second childless contract call must be throttled by the shared CONTRACT_CALL bucket");
                }));
    }
}
