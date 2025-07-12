package com.hedera.node.app.hapi.fees.apis.file;

import com.hedera.node.app.hapi.fees.apis.MockExchangeRate;
import com.hedera.node.app.spi.fees.Fees;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static com.hedera.node.app.hapi.fees.apis.common.FeeConstants.FILE_FREE_BYTES;
import static org.junit.jupiter.api.Assertions.assertEquals;

class FileOperationsTest {

    @Test
    void testFileOperations() {
        FileOperations transfer = new FileOperations("FileCreate", "dummy description");
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 1);

        for (int numBytes = 10; numBytes < 100000; numBytes += 100) {
            params.put("numBytes", numBytes);
            Fees fee = transfer.computeFee(params, new MockExchangeRate().activeRate());

            double overage = (numBytes <= FILE_FREE_BYTES)
                    ? 0.05
                    : ((0.05 + (numBytes - FILE_FREE_BYTES) * 0.000011));
            overage = Math.round(overage * 1000000000) / 1000000000.0;
            assertEquals(overage, fee.usd(), "FILE operation fee - " + numBytes + " bytes");
        }
    }
}
