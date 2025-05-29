package com.hedera.node.app.hapi.fees.apis.token;

import com.hedera.node.app.hapi.fees.FeeResult;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenWipeTest {

    @Test
    void testTokenWipeOne() {
        TokenWipe topic = new TokenWipe();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numTokens", 1);

        FeeResult fee = topic.computeFee(params);
        assertEquals(0.001, fee.fee, "Token Wipe - 1");
    }

    @Test
    void testTokenWipeMultiple() {
        TokenWipe topic = new TokenWipe();
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numTokens", 5);

        FeeResult fee = topic.computeFee(params);
        assertEquals(5 * 0.001, fee.fee, "Token Wipe - multiple");
    }
}
