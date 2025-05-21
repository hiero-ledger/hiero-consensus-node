// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Load and makes available the ops duration schedule for the Hedera EVM.
 */
public class HederaOpsDuration {
    public static final String HEDERA_OPS_DURATION = "hedera-ops-duration.json";
    public static final String OP_DURATION_MULTIPLIER_KEY = "ops";
    public static final String PRECOMPILE_MULTIPLIER_KEY = "precompile";
    public static final String SYSTEM_CONTRACT_MULTIPLIER_KEY = "systemContract";
    // As floating point values cannot be used, we use a factor of 100 to use integers.
    public static final long MULTIPLIER_FACTOR = 100;

    private final Supplier<InputStream> source;
    private final ObjectMapper mapper;

    private HederaOpsDurationData hederaOpsDurationData;

    public HederaOpsDuration(Supplier<InputStream> source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    public void loadOpsDuration() {
        try (InputStream in = source.get()) {
            hederaOpsDurationData = mapper.readValue(in, HederaOpsDurationData.class);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read duration file: hedera-ops-duration.json", e);
        }
    }

    public Map<Integer, Long> getOpsDuration() {
        return requireNonNull(hederaOpsDurationData).getOpsDuration();
    }

    private Map<String, Long> getGasBasedDurationMultiplier() {
        return requireNonNull(hederaOpsDurationData).getGasBasedDurationMultiplier();
    }

    public long opsDurationMultiplier() {
        return getGasBasedDurationMultiplier().getOrDefault(OP_DURATION_MULTIPLIER_KEY, 1L);
    }

    public long precompileDurationMultiplier() {
        return getGasBasedDurationMultiplier().getOrDefault(PRECOMPILE_MULTIPLIER_KEY, 1L);
    }

    public long systemContractDurationMultiplier() {
        return getGasBasedDurationMultiplier().getOrDefault(SYSTEM_CONTRACT_MULTIPLIER_KEY, 1L);
    }
}
