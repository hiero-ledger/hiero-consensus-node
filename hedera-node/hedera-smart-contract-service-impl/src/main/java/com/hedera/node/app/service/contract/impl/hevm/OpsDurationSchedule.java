// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.hevm;

import static com.hedera.node.app.hapi.utils.CommonUtils.productWouldOverflow;

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

    /**
     * Computes the gas-based ops-duration units for a call, in an overflow-safe and non-negative way.
     *
     * <p>The raw formula is {@code gasCost * multiplier / multipliersDenominator}. Because some callers
     * can report a very large {@code gasCost} (up to {@link Long#MAX_VALUE}), the naive multiplication
     * would overflow a signed 64-bit long and could even wrap negative. We therefore saturate the product
     * at {@link Long#MAX_VALUE} and treat any non-positive input as contributing zero duration.
     *
     * @param gasCost the gas cost of the call
     * @param multiplier the gas-based duration multiplier to apply
     * @return the ops-duration units, always in {@code [0, Long.MAX_VALUE]}
     */
    public long gasBasedOpsDuration(final long gasCost, final long multiplier) {
        if (gasCost <= 0L || multiplier <= 0L) {
            return 0L;
        }
        final long product = productWouldOverflow(gasCost, multiplier) ? Long.MAX_VALUE : gasCost * multiplier;
        // multipliersDenominator is always >= 1 (see fromConfig / EMPTY)
        return product / multipliersDenominator;
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
