// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Load and makes available the ops duration schedule for the Hedera EVM.
 */
public class HederaOpsDuration {
    public static final String HEDERA_OPS_DURATION = "hedera-ops-duration.json";
    private final Supplier<InputStream> source;
    private final ObjectMapper mapper;
    private Map<Integer, Long> opsDuration;

    public HederaOpsDuration(Supplier<InputStream> source, ObjectMapper mapper) {
        this.source = source;
        this.mapper = mapper;
    }

    public Map<Integer, Long> getOpsDuration() {
        if (opsDuration != null) {
            return opsDuration;
        }
        try (InputStream in = source.get()) {
            opsDuration = mapper.readValue(in, new TypeReference<Map<Integer, Long>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Unable to read ops duration: hedera-ops-duration.json", e);
        }
        return opsDuration;
    }
}
