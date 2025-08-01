// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.state;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.EXPLICIT_WRITE_TRACING;
import static com.hedera.node.app.spi.workflows.ResourceExhaustedException.validateResource;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.contract.ContractNonceInfo;
import com.hedera.node.app.service.contract.impl.annotations.TransactionScope;
import com.hedera.node.app.service.contract.impl.exec.scope.HandleHederaOperations;
import com.hedera.node.app.service.contract.impl.infra.IterableStorageManager;
import com.hedera.node.app.service.contract.impl.infra.RentCalculator;
import com.hedera.node.app.service.contract.impl.infra.StorageSizeValidator;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.ResourceExhaustedException;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;

/**
 * A {@link ProxyWorldUpdater} that enforces several Hedera-specific checks and actions before
 * making the final commit in the "base" {@link HandleHederaOperations}. These include validating storage size
 * limits, calculating and charging rent, and preserving per-contract linked lists. See the
 * {@link #commit()} implementation for more details.
 */
@TransactionScope
public class RootProxyWorldUpdater extends ProxyWorldUpdater {
    private final RentCalculator rentCalculator;
    private final ContractsConfig contractsConfig;
    private final IterableStorageManager storageManager;
    private final StorageSizeValidator storageSizeValidator;
    private final HandleContext context;

    private boolean committed = false;

    @Nullable
    private TxStorageUsage txStorageUsage;

    private List<ContractID> createdContractIds;
    private List<ContractNonceInfo> updatedContractNonces = Collections.emptyList();

    @Inject
    public RootProxyWorldUpdater(
            @NonNull final Enhancement enhancement,
            @NonNull final ContractsConfig contractsConfig,
            @NonNull final EvmFrameStateFactory evmFrameStateFactory,
            @NonNull final RentCalculator rentCalculator,
            @NonNull final IterableStorageManager storageManager,
            @NonNull final StorageSizeValidator storageSizeValidator,
            @NonNull final HandleContext context) {
        super(enhancement, evmFrameStateFactory, null);
        this.contractsConfig = requireNonNull(contractsConfig);
        this.storageManager = requireNonNull(storageManager);
        this.rentCalculator = requireNonNull(rentCalculator);
        this.storageSizeValidator = requireNonNull(storageSizeValidator);
        this.context = context;
    }

    /**
     * Before committing the changes to the base scope via {@code super.commit()}, does the following steps:
     * <ol>
     *     <li>Gets the list of pending storage changes and summarizes their effects on size.</li>
     *     <li>Validates the effects on size are legal.</li>
     *     <li>For each increase in storage size, calculates rent and tries to charge the allocating contract.</li>
     *     <li>"Rewrites" the pending storage changes to preserve per-contract linked lists.</li>
     * </ol>
     *
     * @throws ResourceExhaustedException if the storage size limit is exceeded or rent cannot be paid
     */
    @Override
    public void commit() {
        // If not tracing explicit writes, request a set of changed storage keys to use to
        // mark MapUpdateChanges as logically identical in the block stream
        final boolean explicitWriteTracing = Boolean.TRUE.equals(
                context.dispatchMetadata().getMetadataIfPresent(EXPLICIT_WRITE_TRACING, Boolean.class));
        txStorageUsage = evmFrameState.getTxStorageUsage(!explicitWriteTracing);
        final var writes = txStorageUsage.accesses();
        // Validate the effects on size are legal
        final var sizeEffects = summarizeSizeEffects(writes);
        storageSizeValidator.assertValid(
                sizeEffects.finalSlotsUsed(), enhancement.operations(), sizeEffects.sizeChanges());
        // Charge rent for each increase in storage size
        chargeRentFor(sizeEffects);
        // "Rewrite" the pending storage changes to preserve per-contract linked lists
        storageManager.persistChanges(
                enhancement,
                writes,
                sizeEffects.sizeChanges(),
                enhancement.operations().getStore());

        // We now have an apparently valid change set, and want to capture some summary
        // information for the Hedera record
        final var contractChangeSummary = enhancement.operations().summarizeContractChanges();
        createdContractIds = contractChangeSummary.newContractIds();

        if (contractsConfig.enforceCreationThrottle()) {
            final var creationCapacityIsAvailable =
                    !context.throttleAdviser().shouldThrottleNOfUnscaled(createdContractIds.size(), CRYPTO_CREATE);
            validateResource(creationCapacityIsAvailable, CONSENSUS_GAS_EXHAUSTED);
        }

        // If nonces externalization is enabled, we need to capture the updated nonces
        if (contractsConfig.noncesExternalizationEnabled()) {
            updatedContractNonces = contractChangeSummary.updatedContractNonces();
        }

        super.commit();
        // Be sure not to externalize contract ids or nonces without a successful commit
        committed = true;
    }

    @Override
    public @NonNull TxStorageUsage getTxStorageUsage() {
        return requireNonNull(txStorageUsage);
    }

    /**
     * If a successful commit has been made, returns the list of contract ids created during the transaction.
     *
     * @return the list of contract ids created during the transaction
     * @throws IllegalStateException if a commit has not been made successfully
     */
    public @NonNull List<ContractID> getCreatedContractIds() {
        if (!committed) {
            throw new IllegalStateException("No successful commit has been made");
        }
        return createdContractIds;
    }

    /**
     * If a successful commit has been made, returns the map of contract ids to nonces updated during the transaction.
     *
     * @return the list of nonces updated during the transaction
     * @throws IllegalStateException if a commit has not been made successfully
     */
    public List<ContractNonceInfo> getUpdatedContractNonces() {
        if (!committed) {
            throw new IllegalStateException("No successful commit has been made");
        }
        return updatedContractNonces;
    }

    private record SizeEffects(long finalSlotsUsed, List<StorageSizeChange> sizeChanges) {}

    private SizeEffects summarizeSizeEffects(@NonNull final List<StorageAccesses> allChanges) {
        // The initial K/V state will still include the slots being "zeroed out"; i.e., removed
        var finalSlotsUsed = evmFrameState.getKvStateSize();
        final List<StorageSizeChange> sizeChanges = new ArrayList<>();
        for (final var changes : allChanges) {
            final var sizeChange = changes.summarizeSizeEffects();
            sizeChanges.add(sizeChange);
            finalSlotsUsed -= sizeChange.numRemovals();
        }
        return new SizeEffects(finalSlotsUsed, sizeChanges);
    }

    private void chargeRentFor(@NonNull final SizeEffects sizeEffects) {
        for (final var sizeChange : sizeEffects.sizeChanges()) {
            if (sizeChange.numAdded() > 0) {
                final var rentFactors = evmFrameState.getRentFactorsFor(sizeChange.contractID());
                // Calculate rent and try to charge the allocating contract
                final var rentInTinycents = rentCalculator.computeFor(
                        sizeEffects.finalSlotsUsed(),
                        sizeChange.numAdded(),
                        rentFactors.numSlotsUsed(),
                        rentFactors.expiry());
                final var rentInTinybars = enhancement.operations().valueInTinybars(rentInTinycents);
                enhancement.operations().chargeStorageRent(sizeChange.contractID(), rentInTinybars, true);
            }
        }
    }
}
