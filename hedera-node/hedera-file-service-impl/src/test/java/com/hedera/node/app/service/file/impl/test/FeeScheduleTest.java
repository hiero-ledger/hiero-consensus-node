// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.io.IOException;
import org.hiero.hapi.fees.FeeScheduleUtils;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.hiero.hapi.support.fees.ServiceFeeSchedule;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FeeScheduleUtils#isValid(FeeSchedule)} validating all HIP-1261 rules.
 */
public class FeeScheduleTest {

    /**
     * Creates a minimal valid fee schedule that passes all HIP-1261 validation rules.
     * This can be used as a base for negative test cases.
     */
    private FeeSchedule createMinimalValidSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.KEYS, 1),
                        makeExtraDef(Extra.STATE_BYTES, 1),
                        makeExtraDef(Extra.SIGNATURES, 1))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService(
                        "Crypto",
                        makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 100, makeExtraIncluded(Extra.KEYS, 1))))
                .build();
    }

    @Test
    void testLoadingFeeScheduleFromJson() throws ParseException, IOException {
        try (var fin = FeeScheduleTest.class.getClassLoader().getResourceAsStream("genesis/simpleFeesSchedules.json")) {
            assertNotNull(fin, "Test resource genesis/simpleFeesSchedules.json is missing");
            final var buf = FeeSchedule.JSON.parse(new ReadableStreamingData(fin));
            assertTrue(FeeScheduleUtils.isValid(buf), "Fee schedule validation failed");
        }
    }

    @Test
    void validSchedulePasses() {
        FeeSchedule validSchedule = createMinimalValidSchedule();
        assertTrue(FeeScheduleUtils.isValid(validSchedule), "Valid schedule should pass validation");
    }

    @Test
    void catchMissingNode() {
        FeeSchedule badSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1))
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .services(makeService("Crypto", makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 100)))
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule without node should fail validation");
    }

    @Test
    void catchMissingNetwork() {
        FeeSchedule badSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(100).build())
                .services(makeService("Crypto", makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 100)))
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule without network should fail validation");
    }

    @Test
    void catchNegativeValue() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, -88))
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule validation didn't catch negative value");
    }

    @Test
    void catchZeroExtraFee() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 0))
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule with zero extra fee should fail validation");
    }

    @Test
    void catchNegativeBaseFeeInNode() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(-1).build())
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with negative node baseFee should fail validation");
    }

    @Test
    void catchNegativeBaseFeeInService() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .services(makeService("Crypto", makeServiceFee(HederaFunctionality.CRYPTO_CREATE, -1)))
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with negative service baseFee should fail validation");
    }

    @Test
    void catchZeroMultiplier() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(0).build())
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule with zero multiplier should fail validation");
    }

    @Test
    void catchNegativeMultiplier() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(-1).build())
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule), "Fee schedule with negative multiplier should fail validation");
    }

    @Test
    void catchNegativeIncludedCountInService() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .services(makeService(
                        "Crypto",
                        makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 100, makeExtraIncluded(Extra.KEYS, -1))))
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with negative includedCount in service should fail validation");
    }

    @Test
    void catchNegativeIncludedCountInNode() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, -1))
                        .build())
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with negative includedCount in node should fail validation");
    }

    @Test
    void catchDuplicateExtraNames() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1), makeExtraDef(Extra.KEYS, 2))
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with duplicate extra names should fail validation");
    }

    @Test
    void catchDuplicateServiceNames() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .services(
                        makeService("Crypto", makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 100)),
                        makeService("Crypto", makeServiceFee(HederaFunctionality.CRYPTO_DELETE, 100)))
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with duplicate service names should fail validation");
    }

    @Test
    void catchDuplicateTransactionNames() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .services(makeService(
                        "Crypto",
                        makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 100),
                        makeServiceFee(HederaFunctionality.CRYPTO_CREATE, 200)))
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with duplicate transaction names in service should fail validation");
    }

    @Test
    void catchMissingExtraDef() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1))
                .services(makeService(
                        "Consensus",
                        makeServiceFee(
                                HederaFunctionality.CONSENSUS_CREATE_TOPIC,
                                22,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.SIGNATURES, 1))))
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule), "Fee schedule validation failed to find the missing extras def");
    }

    @Test
    void catchMissingExtraDefInService() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1), makeExtraDef(Extra.SIGNATURES, 1))
                .services(makeService(
                        "Crypto",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_CREATE,
                                100,
                                makeExtraIncluded(Extra.STATE_BYTES, 10)))) // BYTES not defined
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with service referencing undefined extra should fail validation");
    }

    @Test
    void catchMissingExtraDefInNode() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.STATE_BYTES, 10)) // BYTES not defined
                        .build())
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with node referencing undefined extra should fail validation");
    }

    @Test
    void catchDuplicateExtraRefInService() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .services(makeService(
                        "Crypto",
                        makeServiceFee(
                                HederaFunctionality.CRYPTO_CREATE,
                                100,
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.KEYS, 2)))) // Duplicate reference
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with duplicate extra references in service should fail validation");
    }

    @Test
    void catchDuplicateExtraRefInNode() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(100)
                        .extras(makeExtraIncluded(Extra.SIGNATURES, 1), makeExtraIncluded(Extra.SIGNATURES, 2))
                        .build())
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with duplicate extra references in node should fail validation");
    }

    @Test
    void catchEmptyServiceSchedule() {
        FeeSchedule badSchedule = createMinimalValidSchedule()
                .copyBuilder()
                .services(
                        ServiceFeeSchedule.DEFAULT.copyBuilder().name("Crypto").build())
                .build();
        assertFalse(
                FeeScheduleUtils.isValid(badSchedule),
                "Fee schedule with empty service schedule should fail validation");
    }

    @Test
    void catchNoServices() {
        FeeSchedule badSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, 1))
                .node(NodeFee.DEFAULT.copyBuilder().baseFee(100).build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(1).build())
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule with no services should fail validation");
    }
}
