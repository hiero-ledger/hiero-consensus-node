// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TestJsonConfig {

    @BeforeEach
    void setUp() throws Exception {}

    @AfterEach
    void tearDown() throws Exception {}

    @Test
    void test() throws JsonParseException, JsonMappingException, IOException {
        System.out.println("Working Directory = " + System.getProperty("user.dir"));
        ObjectMapper objectMapper =
                new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SuperConfig config =
                objectMapper.readValue(ResourceLoader.loadFileAsStream("PlatformTestTemplate.json"), SuperConfig.class);
        System.out.println(config.toString());
    }
}
