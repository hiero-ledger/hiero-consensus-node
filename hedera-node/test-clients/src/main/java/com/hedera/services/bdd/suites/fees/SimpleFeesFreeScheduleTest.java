// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedContractCreateSimpleFeesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.getChargedGasForContractCreate;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.getGasUsedForContractCreate;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.GAS_FEE_USD;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
@OrderedInIsolation
public class SimpleFeesFreeScheduleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CONTRACT_CREATE = "CreateTrivial";
    private static final String CONTRACT = "EmptyOne";

    private static final String CALL_CONTRACT = "SmartContractsFees";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @HapiTest
    final Stream<DynamicTest> runContractCreateWithNormalFees() {
        final var gasUsedRef = new AtomicReference<>(0L);
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                withOpContext((spec, opLog) -> saveFeeSchedule(spec, originalSimpleFeeSchedule)),
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, simpleFeesNormal())),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT)
                        .adminKey(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .gas(200_000L)
                        .via("createTxn"),
                withOpContext((spec, op) -> gasUsedRef.set(getGasUsedForContractCreate(spec, "createTxn"))),
                validateChargedUsdWithinWithTxnSize(
                        "createTxn",
                        txnSize -> {
                            var ret = expectedContractCreateSimpleFeesUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize));
                            ret += gasUsedRef.get() * GAS_FEE_USD;
                            return ret;
                        },
                        0.01),
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, originalSimpleFeeSchedule.get())));
    }

    @HapiTest
    final Stream<DynamicTest> runContractCreateWithCheapGas() {
        final var gasUsedRef = new AtomicReference<>(0L);
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        final double CHEAP_GAS_FEE_USD = 0.000_000_010_0;
        return hapiTest(
                withOpContext((spec, opLog) -> saveFeeSchedule(spec, originalSimpleFeeSchedule)),
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, simpleFeesCheapGas())),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT)
                        .adminKey(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .gas(200_000L)
                        .via("createTxn"),
                withOpContext((spec, op) -> gasUsedRef.set(getGasUsedForContractCreate(spec, "createTxn"))),
                validateChargedUsdWithinWithTxnSize(
                        "createTxn",
                        txnSize -> {
                            var ret = expectedContractCreateSimpleFeesUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize));
                            ret += gasUsedRef.get() * CHEAP_GAS_FEE_USD;
                            return ret;
                        },
                        0.01),
                // restore the original fee schedule
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, originalSimpleFeeSchedule.get())));
    }

    @HapiTest
    final Stream<DynamicTest> runContractCreateWithFreeFees() {
        final var gasUsedRef = new AtomicReference<>(0.0);
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                withOpContext((spec, opLog) -> saveFeeSchedule(spec, originalSimpleFeeSchedule)),
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, simpleFeesWithEverythingFree())),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT)
                        .adminKey(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .gas(200_000L)
                        .via("createTxn"),
                withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                validateChargedUsdWithinWithTxnSize("createTxn", txnSize -> 0, 0.01),
                // restore the original fee schedule
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, originalSimpleFeeSchedule.get())));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesAreFree"})
    final Stream<DynamicTest> runContractCreateWithGlobalFreeBoolean() {
        return hapiTest(
                overriding("fees.simpleFeesAreFree", "true"),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT),
                contractCreate(CONTRACT)
                        .adminKey(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .gas(200_000L)
                        .via("createTxn"),
                validateChargedUsdWithinWithTxnSize("createTxn", txnSize -> 0, 0.01));
    }

    @HapiTest
    final Stream<DynamicTest> runFreeContractCreateAndPaidContractCall() {
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                withOpContext((spec, opLog) -> saveFeeSchedule(spec, originalSimpleFeeSchedule)),
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, simpleFeesWithContractCreateFree())),
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                uploadInitCode(CONTRACT_CREATE),
                contractCreate(CONTRACT_CREATE)
                        .adminKey(KeyFactory.KeyType.THRESHOLD)
                        .via("createTxn"),
                validateChargedUsdWithinWithTxnSize("createTxn", txnSize -> 0.01993, 1),
                contractCall(CONTRACT_CREATE, "create").gas(785_000).via("callTxn"),
                validateChargedUsdWithinWithTxnSize("callTxn", txnSize -> 0, 0.01),
                // restore the original fee schedule
                withOpContext((spec, opLog) -> swapSimpleFeeSchedule(spec, originalSimpleFeeSchedule.get())));
    }

    private static void swapSimpleFeeSchedule(HapiSpec spec, ByteString newFeeSchedule) {
        allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, newFeeSchedule));
        assertTrue(spec.tryReinitializingFees(), "Failed to reinitialize fees after overriding simple fee schedule");
    }

    private static void saveFeeSchedule(HapiSpec spec, AtomicReference<ByteString> originalSimpleFeeSchedule) {
        // save the original fee schedule
        allRunFor(
                spec,
                getFileContents(SIMPLE_FEE_SCHEDULE)
                        .consumedBy(bytes -> originalSimpleFeeSchedule.set(ByteString.copyFrom(bytes))));
    }

    private static ByteString simpleFeesNormal() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            final var pbjSimpleFees = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(
                    FeeSchedule.PROTOBUF.toBytes(pbjSimpleFees).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to build Simple Fees schedule with everything free", e);
        }
    }

    private static ByteString simpleFeesCheapGas() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            for (final var extra : root.path("extras")) {
                if (extra instanceof ObjectNode objectNode) {
                    if (objectNode.path("name").asText().equals("GAS")) {
                        objectNode.put("fee", 100);
                    }
                }
            }
            final var pbjSimpleFees = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(
                    FeeSchedule.PROTOBUF.toBytes(pbjSimpleFees).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to build Simple Fees schedule with everything free", e);
        }
    }

    private static ByteString simpleFeesWithEverythingFree() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            for (final var service : root.path("services")) {
                for (final var schedule : service.path("schedule")) {
                    if (schedule instanceof ObjectNode objectNode) {
                        objectNode.put("free", true);
                        objectNode.put("nodeNetworkFeeExempt", true);
                    }
                }
            }
            final var pbjSimpleFees = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(
                    FeeSchedule.PROTOBUF.toBytes(pbjSimpleFees).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to build Simple Fees schedule with everything free", e);
        }
    }

    private static ByteString simpleFeesWithContractCreateFree() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            for (final var service : root.path("services")) {
                if (service.get("name").toString().equals("\"Contract\"")) {
                    for (final var schedule : service.path("schedule")) {
                        if (schedule.get("name").toString().equals("\"ContractCall\"")) {
                            if (schedule instanceof ObjectNode objectNode) {
                                objectNode.put("free", true);
                                objectNode.put("nodeNetworkFeeExempt", true);
                            }
                        }
                    }
                }
            }
            final var pbjSimpleFees = FeeSchedule.JSON.parse(Bytes.wrap(MAPPER.writeValueAsBytes(root)));
            return ByteString.copyFrom(
                    FeeSchedule.PROTOBUF.toBytes(pbjSimpleFees).toByteArray());
        } catch (final Exception e) {
            throw new IllegalStateException("Unable to build Simple Fees schedule without CryptoCall free", e);
        }
    }
}
