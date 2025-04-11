// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Load and makes available the gas schedule for the Hedera EVM.
 */
public class HederaGasSchedule {
    public static final String HEDERA_GAS_SCHEDULE = "hedera-gas-schedule.json";
    private final Supplier<InputStream> source;
    private final ObjectMapper mapper;
    private Map<Integer, Long> gasSchedule;

    public HederaGasSchedule(Supplier<InputStream> source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Map<Integer, Long> getGasSchedule() {
        if (gasSchedule != null) {
            return gasSchedule;
        }
        try (InputStream in = source.get()) {
            gasSchedule = mapper.readValue(in, new TypeReference<Map<Integer, Long>>() {});
        } catch (Exception e) {
            // if an exception occurs, return set the gas schedule to an empty map
            // this will cause the Hedera EVM to use the default gas schedule
            gasSchedule = new HashMap<>();
        }
        return gasSchedule;
    }
}
