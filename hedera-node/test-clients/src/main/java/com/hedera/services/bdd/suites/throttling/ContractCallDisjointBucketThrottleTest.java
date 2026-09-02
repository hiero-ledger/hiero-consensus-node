// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil.asHeadlongAddress;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.DynamicTest;

/**
 * Regression tests that a top-level {@code CONTRACT_CALL} is charged its own consensus-throttle capacity even when
 * it dispatches a native child, using a throttle configuration where {@code ContractCall} occupies its own bucket
 * separate from the child's bucket.
 *
 * <p>{@code tiny-contract-call-consensus-throttle.json} places {@code ContractCall} alone in a 1-op
 * {@code IsolatedContractCallLimit} bucket, while {@code TokenMint} (the child) and other operations live in a
 * separate {@code BulkLimits} bucket. Because the two buckets are disjoint, the child dispatch does not add to the
 * {@code ContractCall} bucket, so this configuration isolates whether the parent call's own bucket unit is
 * counted. A shared-bucket variant, closer to the mainnet layout, is covered by
 * {@link ContractCallParentThrottleUnitTest}.
 *
 * <p>Each top-level {@code CONTRACT_CALL} consumes one unit of its own bucket, so on a 1-op bucket only the first
 * call succeeds and the rest are {@code THROTTLED_AT_CONSENSUS}.
 *
 * <h2>Isolation</h2>
 * The burst is paid by a freshly created civilian account (entity num &gt; {@code accounts.lastThrottleExempt} =
 * 100, so it is not throttle-exempt) and submitted to a non-default node ({@code 0.0.4}) via {@code setNode("4")}
 * so the per-node ingest checks are skipped, leaving only the backend consensus throttle in effect. Setup
 * operations run on the default node with the throttle-exempt payer, so they do not consume the small bucket.
 * {@code contracts.throttle.throttleByGas} is left at its default {@code false} (so a throttled contract call
 * resolves to {@code THROTTLED_AT_CONSENSUS}); the ops-duration throttle is left at its default (a handful of
 * small calls cannot exhaust it).
 *
 * <h2>Run command</h2>
 * <pre>
 * ./gradlew --no-daemon :test-clients:testEmbedded \
 *   --tests 'com.hedera.services.bdd.suites.throttling.ContractCallDisjointBucketThrottleTest'
 * </pre>
 */
public class ContractCallDisjointBucketThrottleTest {
    private static final Logger LOG = LogManager.getLogger(ContractCallDisjointBucketThrottleTest.class);

    private static final int BURST = 4;

    private static final String CIVILIAN = "civilian";
    private static final String TOKEN_TREASURY = "tokenTreasury";
    private static final String MULTI_KEY = "multiKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String NFT = "nft";
    private static final String MINT_CONTRACT = "ConsensusMintCheck";
    private static final String STORAGE_CONTRACT = "Storage";

    private static final String THROTTLES = "testSystemFiles/tiny-contract-call-consensus-throttle.json";

    /**
     * A burst of top-level {@code CONTRACT_CALL}s that each dispatch one {@code TokenMint} child. Each call
     * consumes its own {@code ContractCall} bucket unit, so on the 1-op bucket the first succeeds and the
     * remaining {@value #BURST}-1 are {@code THROTTLED_AT_CONSENSUS}.
     */
    @LeakyEmbeddedHapiTest(
            reason = {MUST_SKIP_INGEST},
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.throttle.throttleByGas"},
            throttles = THROTTLES)
    final Stream<DynamicTest> contractCallDispatchingMintChildIsThrottledAfterFirst() {
        final Map<String, ResponseCodeEnum> seen = new LinkedHashMap<>();
        return hapiTest(
                overriding("contracts.throttle.throttleByGas", "false"),
                newKeyNamed(MULTI_KEY),
                cryptoCreate(TOKEN_TREASURY),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(MINT_CONTRACT),
                contractCreate(MINT_CONTRACT).gas(3_000_000L),
                newKeyNamed(SUPPLY_KEY).shape(CONTRACT.signedWith(MINT_CONTRACT)),
                tokenCreate(NFT)
                        .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                        .supplyType(TokenSupplyType.INFINITE)
                        .initialSupply(0)
                        .treasury(TOKEN_TREASURY)
                        .adminKey(MULTI_KEY)
                        .supplyKey(SUPPLY_KEY),
                // Recreate all buckets with zero usage immediately before the burst.
                overridingThrottles(THROTTLES),
                withOpContext((spec, opLog) -> {
                    final var tokenAddr =
                            asHeadlongAddress(asAddress(spec.registry().getTokenID(NFT)));
                    final List<SpecOperation> ops = new ArrayList<>();
                    for (int i = 0; i < BURST; i++) {
                        ops.add(contractCall(MINT_CONTRACT, "mintAndMaybeRevert", tokenAddr, false, new byte[][] {
                                    {(byte) 0xAB}
                                })
                                .gas(2_000_000L)
                                .payingWith(CIVILIAN)
                                .signedBy(CIVILIAN)
                                .setNode("4")
                                .deferStatusResolution()
                                .via("m" + i));
                    }
                    for (int i = 0; i < BURST; i++) {
                        final String txn = "m" + i;
                        ops.add(getTxnRecord(txn)
                                .exposingTo(r -> seen.put(txn, r.getReceipt().getStatus())));
                    }
                    allRunFor(spec, ops.toArray(new SpecOperation[0]));
                }),
                doingContextual(spec -> {
                    LOG.info("[contractCall dispatching mint child] statuses = {}", seen);
                    assertEquals(
                            SUCCESS, seen.get("m0"), "the first contract call dispatching a mint child should succeed");
                    for (int i = 1; i < BURST; i++) {
                        assertEquals(
                                THROTTLED_AT_CONSENSUS,
                                seen.get("m" + i),
                                "contract call #" + i + " must be throttled: its own CONTRACT_CALL unit is counted "
                                        + "even when it dispatches a native child");
                    }
                }));
    }

    /**
     * Control. Two top-level {@code CONTRACT_CALL}s that dispatch no child ({@code Storage.store}, a plain
     * SSTORE). The first succeeds and the second is {@code THROTTLED_AT_CONSENSUS}, confirming the
     * {@code CONTRACT_CALL} bucket is enforced.
     */
    @LeakyEmbeddedHapiTest(
            reason = {MUST_SKIP_INGEST},
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.throttle.throttleByGas"},
            throttles = THROTTLES)
    final Stream<DynamicTest> contractCallWithNoChildIsThrottled() {
        final Map<String, ResponseCodeEnum> seen = new LinkedHashMap<>();
        return hapiTest(
                overriding("contracts.throttle.throttleByGas", "false"),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                uploadInitCode(STORAGE_CONTRACT),
                contractCreate(STORAGE_CONTRACT).gas(2_000_000L),
                // Recreate all buckets with zero usage immediately before the burst.
                overridingThrottles(THROTTLES),
                contractCall(STORAGE_CONTRACT, "store", BigInteger.valueOf(1L))
                        .gas(500_000L)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .setNode("4")
                        .deferStatusResolution()
                        .via("c0"),
                contractCall(STORAGE_CONTRACT, "store", BigInteger.valueOf(2L))
                        .gas(500_000L)
                        .payingWith(CIVILIAN)
                        .signedBy(CIVILIAN)
                        .setNode("4")
                        .deferStatusResolution()
                        .via("c1"),
                getTxnRecord("c0").exposingTo(r -> seen.put("c0", r.getReceipt().getStatus())),
                getTxnRecord("c1").exposingTo(r -> seen.put("c1", r.getReceipt().getStatus())),
                doingContextual(spec -> {
                    LOG.info("[contractCall no-child control] statuses = {}", seen);
                    assertEquals(SUCCESS, seen.get("c0"), "first childless contract call should succeed");
                    assertEquals(
                            THROTTLED_AT_CONSENSUS,
                            seen.get("c1"),
                            "second childless contract call must be throttled by the 1-op CONTRACT_CALL bucket");
                }));
    }
}
