// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees.apis.file;

import static com.hedera.hapi.node.base.HederaFunctionality.FILE_APPEND;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_DELETE;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_CONTENTS;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_GET_INFO;
import static com.hedera.hapi.node.base.HederaFunctionality.FILE_UPDATE;
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

public class FileTests {
    static FeeSchedule feeSchedule;

    @BeforeAll
    static void setup() {
        feeSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.SIGNATURES, 1), makeExtraDef(Extra.BYTES, 1), makeExtraDef(Extra.KEYS, 1))
                .node(NodeFee.DEFAULT
                        .copyBuilder()
                        .baseFee(1)
                        .extras(makeExtraIncluded(Extra.BYTES, 10), makeExtraIncluded(Extra.SIGNATURES, 1))
                        .build())
                .network(NetworkFee.DEFAULT.copyBuilder().multiplier(2).build())
                .services(makeService(
                        "File",
                        makeServiceFee(
                                FILE_CREATE,
                                11,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1024)),
                        makeServiceFee(
                                FILE_UPDATE,
                                11,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1024)),
                        makeServiceFee(
                                FILE_APPEND,
                                11,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1024)),
                        makeServiceFee(
                                FILE_DELETE,
                                11,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1024)),
                        makeServiceFee(
                                FILE_GET_CONTENTS,
                                11,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1024)),
                        makeServiceFee(
                                FILE_GET_INFO,
                                11,
                                makeExtraIncluded(Extra.SIGNATURES, 1),
                                makeExtraIncluded(Extra.KEYS, 1),
                                makeExtraIncluded(Extra.BYTES, 1024))))
                .build();
    }

    @Test
    void fileCreateFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_CREATE);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 500L);
        params.put(Extra.KEYS, 1L);
        assertTrue(validate(feeSchedule), "Fee schedule failed validation");
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(11 + 0 + (1 + 500 - 10) * 3, fee.total());
    }

    @Test
    void fileCreateBigFileFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_CREATE);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 2000L);
        params.put(Extra.KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(11 + (2000 - 1024) * 1 + (1 + 2000 - 10) * 3, fee.total());
    }

    @Test
    void fileUpdateFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_UPDATE);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 500L);
        params.put(Extra.KEYS, 1L);
        assertTrue(validate(feeSchedule), "Fee schedule failed validation");
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(11 + 0 + (1 + 500 - 10) * 3, fee.total());
    }

    @Test
    void fileDeleteFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_DELETE);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 100L);
        params.put(Extra.KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(11 + 0 + (1 + 100 - 10) * 3, fee.total());
    }

    @Test
    void fileAppendFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_APPEND);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 3000L);
        params.put(Extra.KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        assertEquals(11 + (3000 - 1024) * 1 + (1 + 3000 - 10) * 3, fee.total());
    }

    @Test
    void fileGetContentsFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_GET_CONTENTS);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 3000L);
        params.put(Extra.KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        System.out.println(fee);
        assertEquals(11 + (3000 - 1024) * 1 + (1 + 3000 - 10) * 3, fee.total());
    }

    @Test
    void fileGetInfoFee() {
        FeeModel model = FeeModelRegistry.lookupModel(FILE_GET_INFO);
        Map<Extra, Object> params = new HashMap<>();
        params.put(Extra.SIGNATURES, 1L);
        params.put(Extra.BYTES, 100L);
        params.put(Extra.KEYS, 1L);
        FeeResult fee = model.computeFee(params, feeSchedule);
        System.out.println(fee);
        assertEquals(11 + (1 + 100 - 10) * 3, fee.total());
    }
}
