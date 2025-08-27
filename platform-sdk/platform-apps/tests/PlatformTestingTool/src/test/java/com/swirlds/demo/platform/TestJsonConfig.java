// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestJsonConfig {

    @BeforeEach
    void setUp() {}

    @AfterEach
    void tearDown() {}

    @Test
    void test() throws IOException {
        System.out.println("Working Directory = " + System.getProperty("user.dir"));
        ObjectMapper objectMapper =
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SuperConfig config = objectMapper.readValue(
                TestJsonConfig.class.getResourceAsStream("/PlatformTestTemplate.json"), SuperConfig.class);
        System.out.println(config.toString());
    }
}
