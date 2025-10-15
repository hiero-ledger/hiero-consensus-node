// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.consensus;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_DELETE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_SUBMIT_MESSAGE;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.hiero.hapi.fees.FeeScheduleUtils.validate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.hiero.hapi.fees.FeeModel;
import org.hiero.hapi.fees.FeeModelRegistry;
import org.hiero.hapi.fees.FeeResult;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ConsensusFeeModelTests {
    static FeeSchedule feeSchedule;

    @BeforeAll
    static void setup() {
        feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(
                        makeExtraDef(Extra.BYTES, 1),
                        makeExtraDef(Extra.KEYS, 2),
                        makeExtraDef(Extra.SIGNATURES, 3),
                        makeExtraDef(Extra.CUSTOM_FEE, 500))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(1)
                        .extras(makeExtraIncluded(Extra.BYTES, 10), makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "Consensus",
                        makeServiceFee(CONSENSUS_CREATE_TOPIC, 15, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(CONSENSUS_UPDATE_TOPIC, 22, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(CONSENSUS_DELETE_TOPIC, 10, makeExtraIncluded(Extra.KEYS, 1)),
                        makeServiceFee(
                                CONSENSUS_SUBMIT_MESSAGE,
                                33,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 100),
                                makeExtraIncluded(Extra.CUSTOM_FEE, 0))))
                .build();
    }

    @Test
    void createTopicWithExtraKeys() {
        FeeModel model = FeeModelRegistry.lookupModel(CONSENSUS_CREATE_TOPIC);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 5L);
        params.put(Extra.BYTES, 20L);
        //        params.put(Params.HasCustomFee, YesOrNo.NO);

        assertTrue(validate(feeSchedule), "Fee schedule failed validation");
        FeeResult fee = model.computeFee(params, feeSchedule);
        // service base fee= 15
        // 5 keys - 1 included * key cost of 2 = 4*2 = 8
        // node base fee = 1
        // node includes 1 sig and 1 bytes for free
        assertEquals(
                15 // service base fee
                        + (5 - 1) * 2 // 5 keys - 1 included * key cost of 2 = 8
                        + (1 + 10) * 3 // node base fee = 1 + (20-10) bytes, x3 to include network
                ,
                fee.total());
        assertEquals(1 + 10, fee.node);
        assertEquals((1 + 10) * 2, fee.network);
    }

    @Test
    void updateTopic() {
        FeeModel model = FeeModelRegistry.lookupModel(CONSENSUS_UPDATE_TOPIC);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);
        params.put(Extra.BYTES, 10L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(22 + 3, fee.total());
    }

    @Test
    void submitMessage() {
        FeeModel model = FeeModelRegistry.lookupModel(CONSENSUS_SUBMIT_MESSAGE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);
        params.put(Extra.BYTES, 100L);
        params.put(Extra.CUSTOM_FEE, 0L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        // base fee for submit = 33
        // node + network = (90+1)*3
        assertEquals(33 + 91 * 3, fee.total());
    }

    @Test
    void submitMessageWithExtraBytes() {
        FeeModel model = FeeModelRegistry.lookupModel(CONSENSUS_SUBMIT_MESSAGE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);
        params.put(Extra.BYTES, 500L);
        params.put(Extra.CUSTOM_FEE, 0L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        // base fee for submit = 33
        // extra bytes = (500-100)*1 = 400
        // node + network = (490+1)*3
        assertEquals(33 + 400 + 491 * 3, fee.total());
    }

    @Test
    void submitMessageWithCustomFee() {
        FeeModel model = FeeModelRegistry.lookupModel(CONSENSUS_SUBMIT_MESSAGE);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);
        params.put(Extra.BYTES, 10L);
        params.put(Extra.CUSTOM_FEE, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        // base fee for submit = 33
        // custom fee surcharge = 500
        // node + network = (1)*3
        assertEquals(33 + 500 + 3, fee.total());
    }

    @Test
    void deleteTopic() {
        FeeModel model = FeeModelRegistry.lookupModel(CONSENSUS_DELETE_TOPIC);
        Map<Extra, Long> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.KEYS, 1L);
        params.put(Extra.BYTES, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        // base fee for submit = 10
        // node + network = (1)*3
        assertEquals(10 + 3, fee.total());
    }
}
