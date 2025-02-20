// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.merkle.map;

import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.Crypto;
import static com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType.FCQ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swirlds.demo.platform.SuperConfig;
import com.swirlds.demo.platform.TestUtil;
import com.swirlds.merkle.test.fixtures.map.lifecycle.EntityType;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FCMConfigTest {
    static final String fcm1KJson = "configs/FCM1KForTest.json";
    static final String multipleJson = "configs/FCMMultipleOperationsForTest.json";

    static FCMConfig fcmConfig;
    /**
     * key: jsonFile name
     * value: a Map which contains expected amount of each entity type per node
     */
    static Map<String, Map<EntityType, Integer>> expectedAmountsForType = new HashMap<>();
    /**
     * key: jsonFile name
     * value: expected total entity amounts per node
     */
    static Map<String, Integer> expectedTotalAmounts = new HashMap<>();

    static {
        Map<EntityType, Integer> expectedFCM1K = new HashMap<>();
        expectedFCM1K.put(Crypto, 7000);
        expectedAmountsForType.put(fcm1KJson, expectedFCM1K);
        expectedTotalAmounts.put(fcm1KJson, 7000);

        Map<EntityType, Integer> expectedMultiple = new HashMap<>();
        expectedMultiple.put(Crypto, 10000);
        expectedMultiple.put(FCQ, 20000);
        expectedAmountsForType.put(multipleJson, expectedMultiple);
        expectedTotalAmounts.put(multipleJson, 30000);
    }

    @Test
    void sequentialsIsNotNull() {
        final FCMConfig config = new FCMConfig();
        assertNotNull(config.getSequentials(), "Sequentials should never be null");
        assertEquals(0, config.getSequentials().length, "Default length for sequentials is 0");
    }

    @ParameterizedTest()
    @ValueSource(strings = {fcm1KJson, multipleJson})
    public void getExpectedEntityAmountForTypePerNodeTest(String jsonPath) throws IOException {
        fcmConfig = readFCMConfig(jsonPath);
        fcmConfig.loadSequentials();
        for (EntityType entityType : EntityType.values()) {
            assertEquals(
                    expectedAmountsForType.get(jsonPath).getOrDefault(entityType, 0),
                    fcmConfig.getExpectedEntityAmountForTypePerNode(entityType));
        }
        assertEquals(expectedTotalAmounts.get(jsonPath), fcmConfig.getExpectedEntityAmountTotalPerNode());
    }

    static FCMConfig readFCMConfig(final String jsonFileName) throws IOException {
        ObjectMapper objectMapper =
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SuperConfig superConfig =
                objectMapper.readValue(new TestUtil().resolveConfigFile(jsonFileName), SuperConfig.class);
        return superConfig.getFcmConfig();
    }
}
