// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.config.data.OpsDurationConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Objects;

public record OpsDurationSchedule(
        /* The main pricing schedule: the index on the list serves as an op code number */
        long[] opsDurationByOpCode,
        /* Fallback gas multiplier for op codes that are missing in opsDurationByOpCode */
        long opsGasBasedDurationMultiplier,
        /* Gas multiplier for precompiles */
        long precompileGasBasedDurationMultiplier,
        /* Gas multiplier for system contracts */
        long systemContractGasBasedDurationMultiplier,
        /* Gas multiplier for account lazy creation (see `ProxyWorldUpdater.tryLazyCreation`) */
        long accountLazyCreationOpsDurationMultiplier,
        /* Denominator for all above multipliers (to be able to configure fractional multipliers) */
        long multipliersDenominator) {

    private static final long DEFAULT_MULTIPLIERS_DENOMINATOR = 100;

    public static OpsDurationSchedule empty() {
        return new OpsDurationSchedule(new long[256], 0, 0, 0, 0, 1);
    }

    public static OpsDurationSchedule fromConfig(@NonNull final OpsDurationConfig opsDurationConfig) {
        final var opsDurationByOpCodeArray = new long[256];
        opsDurationConfig
                .opsDurations1_to_64()
                .forEach(opsDurationPair ->
                        opsDurationByOpCodeArray[opsDurationPair.left().intValue()] = opsDurationPair.right());
        opsDurationConfig
                .opsDurations65_to_128()
                .forEach(opsDurationPair ->
                        opsDurationByOpCodeArray[opsDurationPair.left().intValue()] = opsDurationPair.right());
        opsDurationConfig
                .opsDurations129_to_192()
                .forEach(opsDurationPair ->
                        opsDurationByOpCodeArray[opsDurationPair.left().intValue()] = opsDurationPair.right());
        opsDurationConfig
                .opsDurations193_to_256()
                .forEach(opsDurationPair ->
                        opsDurationByOpCodeArray[opsDurationPair.left().intValue()] = opsDurationPair.right());
        return new OpsDurationSchedule(
                opsDurationByOpCodeArray,
                opsDurationConfig.opsGasBasedDurationMultiplier(),
                opsDurationConfig.precompileGasBasedDurationMultiplier(),
                opsDurationConfig.systemContractGasBasedDurationMultiplier(),
                opsDurationConfig.accountLazyCreationOpsDurationMultiplier(),
                DEFAULT_MULTIPLIERS_DENOMINATOR);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof OpsDurationSchedule that)) return false;
        return multipliersDenominator == that.multipliersDenominator
                && opsGasBasedDurationMultiplier == that.opsGasBasedDurationMultiplier
                && precompileGasBasedDurationMultiplier == that.precompileGasBasedDurationMultiplier
                && systemContractGasBasedDurationMultiplier == that.systemContractGasBasedDurationMultiplier
                && accountLazyCreationOpsDurationMultiplier == that.accountLazyCreationOpsDurationMultiplier
                && Arrays.equals(opsDurationByOpCode, that.opsDurationByOpCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Arrays.hashCode(opsDurationByOpCode),
                opsGasBasedDurationMultiplier,
                precompileGasBasedDurationMultiplier,
                systemContractGasBasedDurationMultiplier,
                accountLazyCreationOpsDurationMultiplier,
                multipliersDenominator);
    }
}
