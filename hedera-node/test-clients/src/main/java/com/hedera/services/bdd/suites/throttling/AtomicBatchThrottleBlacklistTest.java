// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
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
 * Behavioral check that an atomic batch cannot change the throttle definitions. Committed state keeps a restrictive
 * {@code ContractCall} bucket (1 op/s); a batch that would install the permissive mainnet throttles is rejected with
 * {@code BATCH_TRANSACTION_IN_BLACKLIST}. A two-call burst must then still be throttled per committed state: the
 * second {@code ContractCall} is {@code THROTTLED_AT_CONSENSUS}. The burst lands in a single round
 * ({@code deferStatusResolution}), so the outcome is deterministic.
 */
public class AtomicBatchThrottleBlacklistTest {

    private static final String CIVILIAN = "civilian";
    private static final String BROKE = "broke";
    private static final String STORAGE_CONTRACT = "Storage";
    private static final String RESTRICTIVE_THROTTLES = "testSystemFiles/shared-contract-call-child-throttle.json";
    private static final String PERMISSIVE_THROTTLES = "testSystemFiles/mainnet-throttles.json";

    @LeakyEmbeddedHapiTest(
            reason = {MUST_SKIP_INGEST},
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.throttle.throttleByGas"},
            throttles = RESTRICTIVE_THROTTLES)
    final Stream<DynamicTest> throttleRelaxationInBatchIsRejected() {
        final Map<String, ResponseCodeEnum> seen = new LinkedHashMap<>();
        return hapiTest(
                overriding("contracts.throttle.throttleByGas", "false"),
                cryptoCreate(CIVILIAN).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BROKE).balance(0L),
                uploadInitCode(STORAGE_CONTRACT),
                contractCreate(STORAGE_CONTRACT).gas(2_000_000L),
                // Commit the restrictive ContractCall bucket (1 op/s) and reset all buckets to zero usage.
                overridingThrottles(RESTRICTIVE_THROTTLES),
                // A batch that would install the permissive mainnet throttles, followed by an inner that fails, is
                // rejected; committed 0.0.123 and the in-memory buckets keep the restrictive limit. Paid by the
                // throttle-exempt GENESIS so the batch itself is not throttled at ingest under the restrictive
                // bucket.
                atomicBatch(
                                fileUpdate(THROTTLE_DEFS)
                                        .noLogging()
                                        .payingWith(GENESIS)
                                        .contents(protoDefsFromResource(PERMISSIVE_THROTTLES)
                                                .toByteArray())
                                        .hasKnownStatus(REVERTED_SUCCESS)
                                        .batchKey(GENESIS),
                                cryptoTransfer(tinyBarsFromTo(BROKE, FUNDING, ONE_HBAR))
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, BROKE)
                                        .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                        .batchKey(GENESIS))
                        .payingWith(GENESIS)
                        .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                // Two ContractCalls in one round (deferred resolution), by a non-exempt civilian on a non-default
                // node so only the backend consensus throttle applies.
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
                    assertEquals(SUCCESS, seen.get("store0"), "first contract call should succeed");
                    assertEquals(
                            THROTTLED_AT_CONSENSUS,
                            seen.get("store1"),
                            "second contract call must be throttled by the committed restrictive ContractCall "
                                    + "bucket; the rejected batch must not relax it");
                }));
    }
}
