// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.hevm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.node.app.service.contract.impl.hevm.HederaGasSchedule;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class HederaGasScheduleTest {
    @Test
    void loadsGasScheduleSuccessfully() {
        // Prepare a JSON content representing a valid map
        String jsonContent = "{\"100\":10000,\"101\":10100}";
        Supplier<InputStream> streamSupplier = () -> new ByteArrayInputStream(jsonContent.getBytes());
        HederaGasSchedule schedule = new HederaGasSchedule(streamSupplier, new ObjectMapper());

        Map<Integer, Long> result = schedule.getGasSchedule();

        assertEquals(2, result.size());
        assertEquals(10000L, result.get(100));
        assertEquals(10100L, result.get(101));
    }

    @Test
    void returnsEmptyMapOnException() throws IOException {
        // Mock a supplier that throws an exception when get() is called
        Supplier<InputStream> streamSupplier = mock(Supplier.class);
        when(streamSupplier.get()).thenThrow(new RuntimeException("Test exception"));
        HederaGasSchedule schedule = new HederaGasSchedule(streamSupplier, new ObjectMapper());

        Map<Integer, Long> result = schedule.getGasSchedule();

        assertTrue(result.isEmpty());
    }
}
