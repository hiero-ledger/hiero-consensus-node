// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.config.data.ContractsConfig;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;

/**
 * Calculates the rent in tinybars that should be charged to an allocating contract, given
 * the current consensus time and contract service configuration.
 */
@TransactionScope
public class RentCalculator {
    private final Instant consensusNow;
    private final ContractsConfig contractsConfig;

    @Inject
    public RentCalculator(@NonNull final Instant consensusNow, @NonNull final Configuration config) {
        this.consensusNow = requireNonNull(consensusNow);
        this.contractsConfig = requireNonNull(config).getConfigData(ContractsConfig.class);
    }

    /**
     * Calculates the rent in tinybars that should be charged to an allocating contract, given a few facts
     * about what the contract's and the network's storage utilization would be after the allocation.
     *
     * @param totalSlotsUsed the total number of storage slots that would be used by all contracts after the allocation
     * @param contractSlotsAdded the number of storage slots being allocated
     * @param contractSlotsAlreadyUsed the number of storage slots already used by the allocating contract
     * @param expectedSlotExpiry the expected consensus second at which the allocated slots will expire
     * @return the rent in tinybars that should be charged to the allocating contract
     */
    public long computeFor(
            final long totalSlotsUsed,
            final int contractSlotsAdded,
            final int contractSlotsAlreadyUsed,
            final long expectedSlotExpiry) {
        // TODO - fix this before expiry and rent are enabled
        return 0L;
    }
}
