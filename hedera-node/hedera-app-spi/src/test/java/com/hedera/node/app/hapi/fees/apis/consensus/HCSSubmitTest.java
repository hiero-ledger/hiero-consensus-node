package com.hedera.node.app.hapi.fees.apis.consensus;

import com.hedera.node.app.hapi.fees.FeeResult;
import com.hedera.node.app.hapi.fees.apis.common.YesOrNo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.HCS_FREE_BYTES;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HCSSubmitTest {

    @Test
    void testHCSSubmitNoCustomFee() {
        HCSSubmit transfer = new HCSSubmit();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);

        params.put("hasCustomFee", YesOrNo.NO);

        for (int numBytes = 10; numBytes < 1000; numBytes += 10) {
            params.put("numBytes", numBytes);
            FeeResult fee = transfer.computeFee(params);

            double overage = (numBytes <= HCS_FREE_BYTES)
                    ? 0.0001
                    : ((0.0001 + (numBytes - HCS_FREE_BYTES) * 0.000011));
            overage = Math.round(overage * 1000000000) / 1000000000.0;
            assertEquals(overage, fee.fee, "HCS topic Submit without custom fee - " + numBytes + " bytes");
        }
    }

    @Test
    void testHCSSubmitWithCustomFee() {
        HCSSubmit transfer = new HCSSubmit();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);

        params.put("hasCustomFee", YesOrNo.YES);

        for (int numBytes = 10; numBytes < 1000; numBytes += 10) {
            params.put("numBytes", numBytes);
            FeeResult fee = transfer.computeFee(params);

//            double overage = (numBytes <= 128) ? 0.0001 : (((0.0001 + (numBytes - 128) * 0.00001) * 100000000) / 100000000);

            double overage = (numBytes <= HCS_FREE_BYTES)
                    ? 0.05
                    : ((0.05 + (numBytes - HCS_FREE_BYTES) * 0.000011));
            overage = Math.round(overage * 1000000000) / 1000000000.0;
            assertEquals(overage, fee.fee, "HCS topic Submit without custom fee - " + numBytes + " bytes");
        }
    }
}
