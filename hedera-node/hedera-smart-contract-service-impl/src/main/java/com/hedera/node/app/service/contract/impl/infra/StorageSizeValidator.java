// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.node.app.spi.workflows.ResourceExhaustedException.validateResource;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;

/**
 * Validates that a set of storage size changes are valid, given the current contract service configuration.
 */
@TransactionScope
public class StorageSizeValidator {
    private final ContractsConfig contractsConfig;

    @Inject
    public StorageSizeValidator(@NonNull final ContractsConfig contractsConfig) {
        this.contractsConfig = contractsConfig;
    }

    /**
     * Validates that a set of storage size changes are valid, given the current contract service configuration.
     *
     * @param aggregateSlotsUsed the number of slots that would be used by all contracts combined after the transaction
     */
    public void assertValid(final long aggregateSlotsUsed) {
        final var maxAggregateSlots = contractsConfig.maxKvPairsAggregate();
        validateResource(maxAggregateSlots >= aggregateSlotsUsed, MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);
    }
}
