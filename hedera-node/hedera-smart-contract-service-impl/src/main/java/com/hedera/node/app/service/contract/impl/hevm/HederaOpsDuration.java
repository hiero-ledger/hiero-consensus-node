// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.config.data.OpsDurationConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;

/**
 * Load and makes available the ops duration schedule for the Hedera EVM.
 */
public class HederaOpsDuration {
    public static final String OP_DURATION_MULTIPLIER_KEY = "ops";
    public static final String PRECOMPILE_MULTIPLIER_KEY = "precompile";
    public static final String SYSTEM_CONTRACT_MULTIPLIER_KEY = "systemContract";
    // This is used to change the step of when we check the duration of the operations.
    public static final String DURATION_CHECK_SHIFT = "durationCheckShift";
    // As floating point values cannot be used, we use a factor of 100 to use integers.
    public static final long MULTIPLIER_FACTOR = 100;

    private final Map<Integer, Long> opsDuration = new HashMap<>();
    private final Map<String, Long> gasBasedDurationMultiplier = new HashMap<>();

    public void applyDurationFromConfig(@NonNull final OpsDurationConfig opsDurationConfig) {
        opsDurationConfig
                .opsDurations1_to_64()
                .forEach(opsDurPair -> opsDuration.put(opsDurPair.left().intValue(), opsDurPair.right()));
        opsDurationConfig
                .opsDurations65_to_128()
                .forEach(opsDurationPair ->
                        opsDuration.put(opsDurationPair.left().intValue(), opsDurationPair.right()));
        opsDurationConfig
                .opsDurations129_to_192()
                .forEach(opsDurationPair ->
                        opsDuration.put(opsDurationPair.left().intValue(), opsDurationPair.right()));
        opsDurationConfig
                .opsDurations193_to_256()
                .forEach(opsDurationPair ->
                        opsDuration.put(opsDurationPair.left().intValue(), opsDurationPair.right()));
        gasBasedDurationMultiplier.put(OP_DURATION_MULTIPLIER_KEY, opsDurationConfig.opsGasBasedDurationMultiplier());
        gasBasedDurationMultiplier.put(
                PRECOMPILE_MULTIPLIER_KEY, opsDurationConfig.precompileGasBasedDurationMultiplier());
        gasBasedDurationMultiplier.put(
                SYSTEM_CONTRACT_MULTIPLIER_KEY, opsDurationConfig.systemContractGasBasedDurationMultiplier());
    }

    public Map<Integer, Long> getOpsDuration() {
        return opsDuration;
    }

    private Map<String, Long> getGasBasedDurationMultiplier() {
        return gasBasedDurationMultiplier;
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

    public long durationCheckShift() {
        return getGasBasedDurationMultiplier().getOrDefault(DURATION_CHECK_SHIFT, 100L);
    }
}
