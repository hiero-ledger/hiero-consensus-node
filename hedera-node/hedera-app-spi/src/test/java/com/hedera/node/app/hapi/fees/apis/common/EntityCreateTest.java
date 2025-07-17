package com.hedera.node.app.hapi.fees.apis.common;

import com.hedera.node.app.hapi.fees.BaseFeeRegistry;
import com.hedera.node.app.hapi.fees.apis.MockExchangeRate;
import com.hedera.node.app.spi.fees.Fees;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntityCreateTest {

    @Test
    void testEntityCreateDummyService() {
        EntityCreate entity = new EntityCreate("dummyService", "dummyAPI", "dummy description", 5, false);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 7);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(2 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Entity create");
    }

    @Test
    void testEntityCreateDummyServiceWithMultipleSignatures() {
        EntityCreate entity = new EntityCreate("dummyService", "dummyAPI", "dummy description", 5, false);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 10);
        params.put("numKeys", 5);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(4 * BaseFeeRegistry.getBaseFee("PerSignature"), fee.usd(), "Entity Create - multiple signatures");
    }

    @Test
    void testEntityCreateCryptoService() {
        EntityCreate entity = new EntityCreate("Crypto", "CryptoCreate", "Create an account", 2, false);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(BaseFeeRegistry.getBaseFee("CryptoCreate") + 8 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Crypto Create");
    }

    @Test
    void testEntityCreateCustomFeeCapableTokenServiceNoCustomFee() {
        EntityCreate entity = new EntityCreate("Token", "TokenCreate", "Create a token type", 7, true);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);
        params.put("hasCustomFee", YesOrNo.NO);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(BaseFeeRegistry.getBaseFee("TokenCreate") + 3 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Token Create - no custom fee");
    }

    @Test
    void testEntityCreateCustomFeeCapableTokenServiceWithCustomFee() {
        EntityCreate entity = new EntityCreate("Token", "TokenCreate", "Create a token type", 7, true);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);
        params.put("hasCustomFee", YesOrNo.YES);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(BaseFeeRegistry.getBaseFee("TokenCreateWithCustomFee") + 3 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Token Create - has custom fee");
    }

    @Test
    void testEntityCreateCustomFeeCapableTopicServiceNoCustomFee() {
        EntityCreate entity = new EntityCreate("Topic", "ConsensusCreateTopic", "Create a topic", 1, true);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 5);
        params.put("hasCustomFee", YesOrNo.NO);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0 + BaseFeeRegistry.getBaseFee("ConsensusCreateTopic") + 4 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Topic Create - no custom fee");
    }

    @Test
    void testEntityCreateCustomFeeCapableTopicServiceWithCustomFee() {
        EntityCreate entity = new EntityCreate("Topic", "ConsensusCreateTopic", "Create a topic", 1, true);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 5);
        params.put("hasCustomFee", YesOrNo.YES);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0 + BaseFeeRegistry.getBaseFee("ConsensusCreateTopicWithCustomFee") + 4 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Topic Create - with custom fee");
    }

    @Test
    void testEntityCreateContractService() {
        EntityCreate entity = new EntityCreate("Smart Contract", "ContractCreate", "Create a smart contract", 1, false);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 10);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0 + BaseFeeRegistry.getBaseFee("ContractCreate") + 9 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Contract Create");
    }

    @Test
    void testEntityCreateScheduleService() {
        EntityCreate entity = new EntityCreate("Miscellaneous", "ScheduleCreate", "Create a schedule", 1, false);
        Map<String, Object> params = new HashMap<>();
        params.put("numSignatures", 1);
        params.put("numKeys", 5);

        Fees fee = entity.computeFee(params, new MockExchangeRate().activeRate());
        assertEquals(0 + BaseFeeRegistry.getBaseFee("ScheduleCreate") + 4 * BaseFeeRegistry.getBaseFee("PerKey"), fee.usd(), "Contract Create");
    }


}
