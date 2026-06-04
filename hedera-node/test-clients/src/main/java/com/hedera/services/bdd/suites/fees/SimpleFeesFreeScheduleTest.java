package com.hedera.services.bdd.suites.fees;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
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
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.getChargedGasForContractCreate;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
@OrderedInIsolation
public class SimpleFeesFreeScheduleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CONTRACT = "EmptyOne";
    private static final String CALL_CONTRACT = "SmartContractsFees";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @GenesisHapiTest
    final Stream<DynamicTest> runContractCreateWithFreeFees() {
        final var gasUsedRef = new AtomicReference<>(0.0);
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                withOpContext((spec, opLog) -> {
                    // save the original fee schedule
                    allRunFor(
                            spec,
                            getFileContents(SIMPLE_FEE_SCHEDULE)
                                    .consumedBy(bytes -> originalSimpleFeeSchedule.set(ByteString.copyFrom(bytes))));
                    // upload a modified fee schedule
                    allRunFor(
                            spec,
                            updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, simpleFeesWithEverythingFree()));
                    assertTrue(
                            spec.tryReinitializingFees(),
                            "Failed to reinitialize fees after overriding simple fee schedule");
                }),
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
                withOpContext((spec, opLog) -> {
                    allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, originalSimpleFeeSchedule.get()));
                    assertTrue(
                            spec.tryReinitializingFees(),
                            "Failed to reinitialize fees after overriding simple fee schedule");
                })
        );
    }

    private static ByteString simpleFeesWithEverythingFree() {
        try {
            final JsonNode root =
                    MAPPER.readTree(V0490FileSchema.loadResourceInPackage("genesis/simpleFeesSchedules.json"));
            for (final var service : root.path("services")) {
                System.out.println("updating service " + service.get("name"));
                for(final var schedule : service.path("schedule")) {
                    System.out.println("updating schedule " + schedule.get("name"));
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
            throw new IllegalStateException(
                    "Unable to build simple fee schedule without CryptoCreate pricing curve", e);
        }
    }

}
