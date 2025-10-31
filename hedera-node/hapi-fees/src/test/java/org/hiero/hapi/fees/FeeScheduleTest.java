// SPDX-License-Identifier: Apache-2.0
package org.hiero.hapi.fees;

import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.stream.ReadableStreamingData;
import java.util.Objects;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.Test;

public class FeeScheduleTest {

    @Test
    void testLoadingFeeScheduleFromJson() throws ParseException {
        final var fin = FeeScheduleTest.class.getClassLoader().getResourceAsStream("simple-fee-schedule.json");
        final FeeSchedule buf = FeeSchedule.JSON.parse(new ReadableStreamingData(Objects.requireNonNull(fin)));
        assertTrue(FeeScheduleUtils.isValid(buf), "Fee schedule validation failed");
    }

    // extra definitions must have positive values
    @Test
    void catchNegativeValue() {
        FeeSchedule badSchedule = FeeSchedule.DEFAULT
                .copyBuilder()
                .extras(makeExtraDef(Extra.KEYS, -88))
                .build();
        assertFalse(FeeScheduleUtils.isValid(badSchedule), "Fee schedule validation didn't catch negative value");
    }

    // all referenced extras must exist in the defined extras
    @Test
    void catchMissingExtraDef() {
        FeeSchedule badSchedule = FeeSchedule.DEFAULT
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
}
