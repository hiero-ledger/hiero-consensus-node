// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.node.app.service.contract.impl.hevm.HederaOpsDuration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HederaOpsDurationTest {
    @Test
    void testAllDurationsAreLoaded() {
        String json = "{\"opsDuration\":{\"1\":100}," + "\"gasBasedDurationMultiplier\":{\"ops\":566}}";
        Supplier<InputStream> streamSupplier = () -> new ByteArrayInputStream(json.getBytes());
        HederaOpsDuration opsDuration = new HederaOpsDuration(streamSupplier, new ObjectMapper());

        opsDuration.loadOpsDuration();

        assertEquals(100, opsDuration.getOpsDuration().get(1));
        assertEquals(566, opsDuration.opsDurationMultiplier());
    }

    @Test
    void testLoadOpsDurationThrowsOnBrokenJson() {
        String invalidJson = "{\"opsDuration\":";
        Supplier<InputStream> streamSupplier = () -> new ByteArrayInputStream(invalidJson.getBytes());
        HederaOpsDuration opsDuration = new HederaOpsDuration(streamSupplier, new ObjectMapper());

        assertThrows(IllegalStateException.class, opsDuration::loadOpsDuration);
    }
}
