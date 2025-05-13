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
    public static final long DEFAULT_OPS_DURATION = 10000;
    public static final long DEFAULT_PRECOMPILE_DURATION = 20000;
    public static final long DEFAULT_SYSTEM_CONTRACT_DURATION = 30000;

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

    public Map<Integer, Long> getPrecompileDuration() {
        return requireNonNull(hederaOpsDurationData).getPrecompileDuration();
    }

    public Map<Integer, Long> getSystemContractDuration() {
        return requireNonNull(hederaOpsDurationData).getSystemContractDuration();
    }
}
