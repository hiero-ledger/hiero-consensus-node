// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.ids;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.spi.ids.ReadableEntityIdStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A writeable store for entity ids.
 */
public class ReadableEntityIdStoreImpl implements ReadableEntityIdStore {

    /**
     * The underlying data storage class that holds the entity id data.
     */
    private final ReadableSingletonState<EntityNumber> entityIdState;

    private final ReadableSingletonState<EntityCounts> entityCountsState;

    /**
     * Create a new {@link ReadableEntityIdStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableEntityIdStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.entityIdState = states.getSingleton(ENTITY_ID_STATE_ID);
        this.entityCountsState = states.getSingleton(ENTITY_COUNTS_STATE_ID);
    }

    /**
     * Returns the next entity number that will be used.
     *
     * @return the next entity number that will be used
     */
    @Override
    public long peekAtNextNumber() {
        final var oldEntityNum = entityIdState.get();
        return oldEntityNum == null ? 1 : oldEntityNum.number() + 1;
    }

    // Add all getters for number of entities
    @Override
    public long numAccounts() {
        return requireNonNull(entityCountsState.get()).numAccounts();
    }

    @Override
    public long numTokens() {
        return requireNonNull(entityCountsState.get()).numTokens();
    }

    @Override
    public long numFiles() {
        return requireNonNull(entityCountsState.get()).numFiles();
    }

    @Override
    public long numTopics() {
        return requireNonNull(entityCountsState.get()).numTopics();
    }

    @Override
    public long numContractBytecodes() {
        return requireNonNull(entityCountsState.get()).numContractBytecodes();
    }

    @Override
    public long numContractStorageSlots() {
        return requireNonNull(entityCountsState.get()).numContractStorageSlots();
    }

    @Override
    public long numNfts() {
        return requireNonNull(entityCountsState.get()).numNfts();
    }

    @Override
    public long numTokenRelations() {
        return requireNonNull(entityCountsState.get()).numTokenRelations();
    }

    @Override
    public long numAliases() {
        return requireNonNull(entityCountsState.get()).numAliases();
    }

    @Override
    public long numSchedules() {
        return requireNonNull(entityCountsState.get()).numSchedules();
    }

    @Override
    public long numAirdrops() {
        return requireNonNull(entityCountsState.get()).numAirdrops();
    }

    @Override
    public long getCounterFor(final EntityType entityType) {
        final var entityState = requireNonNull(entityCountsState.get());
        return switch (entityType) {
            case ACCOUNT -> entityState.numAccounts();
            case TOKEN -> entityState.numTokens();
            case NODE -> entityState.numNodes();
            case FILE -> entityState.numFiles();
            case TOPIC -> entityState.numTopics();
            case CONTRACT_BYTECODE -> entityState.numContractBytecodes();
            case CONTRACT_STORAGE -> entityState.numContractStorageSlots();
            case NFT -> entityState.numNfts();
            case TOKEN_ASSOCIATION -> entityState.numTokenRelations();
            case ALIAS -> entityState.numAliases();
            case SCHEDULE -> entityState.numSchedules();
            case AIRDROP -> entityState.numAirdrops();
            case STAKING_INFO -> entityState.numStakingInfos();
            case HOOK -> entityState.numHooks();
            case LAMBDA_STORAGE -> entityState.numLambdaStorageSlots();
        };
    }

    @Override
    public long numNodes() {
        return requireNonNull(entityCountsState.get()).numNodes();
    }
}
