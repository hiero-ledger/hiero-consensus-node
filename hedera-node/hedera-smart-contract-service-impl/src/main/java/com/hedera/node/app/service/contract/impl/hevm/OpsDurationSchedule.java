// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import com.hedera.node.config.data.OpsDurationConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record OpsDurationSchedule(
        /* The main pricing schedule: the index on the list serves as an op code number */
        List<Long> opsDurationByOpCode,
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

    private static final OpsDurationSchedule EMPTY =
            new OpsDurationSchedule(Collections.nCopies(256, 0L), 0, 0, 0, 0, 1);

    private static final long DEFAULT_MULTIPLIERS_DENOMINATOR = 100;

    public static OpsDurationSchedule empty() {
        return EMPTY;
    }

    public static OpsDurationSchedule fromConfig(@NonNull final OpsDurationConfig opsDurationConfig) {
        if (opsDurationConfig.opsDurationByOpCode().size() != 256) {
            throw new IllegalArgumentException("Invalid ops duration config: opsDurationByOpCode must contain "
                    + "exactly 256 elements, but it has "
                    + opsDurationConfig.opsDurationByOpCode().size());
        }
        return new OpsDurationSchedule(
                opsDurationConfig.opsDurationByOpCode(),
                opsDurationConfig.opsGasBasedDurationMultiplier(),
                opsDurationConfig.precompileGasBasedDurationMultiplier(),
                opsDurationConfig.systemContractGasBasedDurationMultiplier(),
                opsDurationConfig.accountLazyCreationOpsDurationMultiplier(),
                DEFAULT_MULTIPLIERS_DENOMINATOR);
    }

    public long opCodeCost(int opCode) {
        return opsDurationByOpCode.get(opCode);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof OpsDurationSchedule that)) return false;
        return multipliersDenominator == that.multipliersDenominator
                && opsGasBasedDurationMultiplier == that.opsGasBasedDurationMultiplier
                && precompileGasBasedDurationMultiplier == that.precompileGasBasedDurationMultiplier
                && systemContractGasBasedDurationMultiplier == that.systemContractGasBasedDurationMultiplier
                && accountLazyCreationOpsDurationMultiplier == that.accountLazyCreationOpsDurationMultiplier
                && opsDurationByOpCode.equals(that.opsDurationByOpCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                opsDurationByOpCode,
                opsGasBasedDurationMultiplier,
                precompileGasBasedDurationMultiplier,
                systemContractGasBasedDurationMultiplier,
                accountLazyCreationOpsDurationMultiplier,
                multipliersDenominator);
    }
}
