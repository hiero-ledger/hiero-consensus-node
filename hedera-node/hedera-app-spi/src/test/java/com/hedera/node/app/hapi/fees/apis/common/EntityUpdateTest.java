package com.hedera.node.app.hapi.fees.apis.common;

import com.hedera.node.app.hapi.fees.apis.MockExchangeRate;
import com.hedera.node.app.spi.fees.Fees;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityUpdateTest {

    @Test
    void testEntityUpdateDummyService() {
        EntityUpdate entity = new EntityUpdate("dummyService", "dummyAPI", "dummy description", 5);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 7);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(2 * 0.01, fee.usd(), "Entity update");
    }

    @Test
    void testEntityUpdateDummyServiceWithMultipleSignatures() {
        EntityUpdate entity = new EntityUpdate("dummyService", "dummyAPI", "dummy description", 5);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 10);
        params.put("numKeys", 5);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.0009 /* 9 * 0.0001 */, fee.usd(), "Entity update");
    }

    @Test
    void testEntityUpdateCryptoService() {
        EntityUpdate entity = new EntityUpdate("Crypto", "CryptoUpdate", "Update an account", 1);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.00022 + 9 * 0.01, fee.usd(), "Crypto update");
    }

    @Test
    void testEntityUpdateTokenService() {
        EntityUpdate entity = new EntityUpdate("Token", "TokenUpdate", "Update a token type", 7);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.001 + 3 * 0.01, fee.usd(), "Token update");
    }

    @Test
    void testEntityUpdateTopicService() {
        EntityUpdate entity = new EntityUpdate("Topic", "ConsensusUpdateTopic", "Update a topic", 1);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.00022 + 9 * 0.01, fee.usd(), "Topic update");
    }

    @Test
    void testEntityUpdateContractService() {
        EntityUpdate entity = new EntityUpdate("Smart Contract", "ContractUpdate", "Update a smart contract", 1);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0.116 /* (0.02600 + 9 * 0.01)*/, fee.usd(), "Smart contract update");
    }

}
