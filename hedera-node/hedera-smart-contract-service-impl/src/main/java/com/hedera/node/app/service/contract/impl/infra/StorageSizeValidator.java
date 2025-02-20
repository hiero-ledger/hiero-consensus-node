// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.infra;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_CONTRACT_STORAGE_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED;
import static com.hedera.node.app.spi.workflows.ResourceExhaustedException.validateResource;

import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.scope.HederaOperations;
import com.hedera.node.app.service.contract.impl.state.StorageSizeChange;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
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
     * @param hederaOperations the extended world scope used to create the size changes being validated
     * @param storageSizeChanges the summarized storage size changes to validate
     */
    public void assertValid(
            final long aggregateSlotsUsed,
            @NonNull final HederaOperations hederaOperations,
            @NonNull final List<StorageSizeChange> storageSizeChanges) {
        final var maxAggregateSlots = contractsConfig.maxKvPairsAggregate();
        validateResource(maxAggregateSlots >= aggregateSlotsUsed, MAX_STORAGE_IN_PRICE_REGIME_HAS_BEEN_USED);

        final var maxIndividualSlots = contractsConfig.maxKvPairsIndividual();
        storageSizeChanges.forEach(change -> {
            final var contractSlotsUsed =
                    change.numAdded() + hederaOperations.getOriginalSlotsUsed(change.contractID());
            validateResource(maxIndividualSlots >= contractSlotsUsed, MAX_CONTRACT_STORAGE_EXCEEDED);
        });
    }
}
