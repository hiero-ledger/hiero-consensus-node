// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl;

import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A writeable store for entity ids.
 */
public class WritableEntityIdStoreImpl extends ReadableEntityIdStoreImpl implements WritableEntityIdStore {

    /**
     * The underlying data storage class that holds the entity id data.
     */
    private final WritableSingletonState<EntityNumber> entityIdState;

    private final WritableSingletonState<EntityCounts> entityCountsState;

    /**
     * Create a new {@link WritableEntityIdStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public WritableEntityIdStoreImpl(@NonNull final WritableStates states) {
        super(states);
        requireNonNull(states);
        this.entityIdState = states.getSingleton(ENTITY_ID_STATE_ID);
        this.entityCountsState = states.getSingleton(ENTITY_COUNTS_STATE_ID);
    }

    @Override
    public long peekAtNextNumber() {
        final var oldEntityNum = entityIdState.get();
        return oldEntityNum == null ? 1 : oldEntityNum.number() + 1;
    }

    @Override
    public long incrementAndGet() {
        final var newEntityNum = peekAtNextNumber();
        entityIdState.put(new EntityNumber(newEntityNum));
        return newEntityNum;
    }

    @Override
    public void incrementEntityTypeCount(final EntityType entityType) {
        adjustEntityCount(entityType, 1);
    }

    @Override
    public void adjustEntityCount(final EntityType entityType, final long delta) {
        final var entityCounts = requireNonNull(entityCountsState.get());
        final var newEntityCounts = entityCounts.copyBuilder();
        switch (entityType) {
            case ACCOUNT -> newEntityCounts.numAccounts(entityCounts.numAccounts() + delta);
            case ALIAS -> newEntityCounts.numAliases(entityCounts.numAliases() + delta);
            case TOKEN -> newEntityCounts.numTokens(entityCounts.numTokens() + delta);
            case TOKEN_ASSOCIATION -> newEntityCounts.numTokenRelations(entityCounts.numTokenRelations() + delta);
            case TOPIC -> newEntityCounts.numTopics(entityCounts.numTopics() + delta);
            case FILE -> newEntityCounts.numFiles(entityCounts.numFiles() + delta);
            case CONTRACT_BYTECODE -> newEntityCounts.numContractBytecodes(entityCounts.numContractBytecodes() + delta);
            case CONTRACT_STORAGE ->
                newEntityCounts.numContractStorageSlots(entityCounts.numContractStorageSlots() + delta);
            case NFT -> newEntityCounts.numNfts(entityCounts.numNfts() + delta);
            case SCHEDULE -> newEntityCounts.numSchedules(entityCounts.numSchedules() + delta);
            case AIRDROP -> newEntityCounts.numAirdrops(entityCounts.numAirdrops() + delta);
            case NODE -> newEntityCounts.numNodes(entityCounts.numNodes() + delta);
            case STAKING_INFO -> newEntityCounts.numStakingInfos(entityCounts.numStakingInfos() + delta);
            case HOOK -> newEntityCounts.numHooks(entityCounts.numHooks() + delta);
            case LAMBDA_STORAGE -> newEntityCounts.numLambdaStorageSlots(entityCounts.numLambdaStorageSlots() + delta);
        }
        entityCountsState.put(newEntityCounts.build());
    }

    @Override
    public void decrementEntityTypeCounter(final EntityType entityType) {
        adjustEntityCount(entityType, -1);
    }
}
