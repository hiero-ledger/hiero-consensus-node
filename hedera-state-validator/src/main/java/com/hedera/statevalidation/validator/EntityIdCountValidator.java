// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.statevalidation.validator.EntityIdUniquenessValidator.ENTITY_ID_GROUP;

import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @see LeafBytesValidator
 */
public class EntityIdCountValidator implements LeafBytesValidator {

    public static final String ENTITY_ID_COUNT_NAME = "entityIdCount";

    private EntityCounts entityCounts;

    private final AtomicLong accountCount = new AtomicLong(0);
    private final AtomicLong aliasesCount = new AtomicLong(0);
    private final AtomicLong tokenCount = new AtomicLong(0);
    private final AtomicLong tokenRelCount = new AtomicLong(0);
    private final AtomicLong nftsCount = new AtomicLong(0);
    private final AtomicLong airdropsCount = new AtomicLong(0);
    private final AtomicLong stakingInfoCount = new AtomicLong(0);
    private final AtomicLong topicCount = new AtomicLong(0);
    private final AtomicLong fileCount = new AtomicLong(0);
    private final AtomicLong nodesCount = new AtomicLong(0);
    private final AtomicLong scheduleCount = new AtomicLong(0);
    private final AtomicLong contractStorageCount = new AtomicLong(0);
    private final AtomicLong contractBytecodeCount = new AtomicLong(0);
    private final AtomicLong hookCount = new AtomicLong(0);
    private final AtomicLong evmHookStorageCount = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String getGroup() {
        return ENTITY_ID_GROUP;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getName() {
        return ENTITY_ID_COUNT_NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        final ReadableSingletonState<EntityCounts> entityIdSingleton =
                state.getReadableStates(EntityIdService.NAME).getSingleton(ENTITY_COUNTS_STATE_ID);
        this.entityCounts = Objects.requireNonNull(entityIdSingleton.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        try {
            final StateKey key = StateKey.PROTOBUF.parse(leafBytes.keyBytes());
            switch (key.key().kind()) {
                case TOKENSERVICE_I_ACCOUNTS -> accountCount.incrementAndGet();
                case TOKENSERVICE_I_ALIASES -> aliasesCount.incrementAndGet();
                case TOKENSERVICE_I_TOKENS -> tokenCount.incrementAndGet();
                case TOKENSERVICE_I_TOKEN_RELS -> tokenRelCount.incrementAndGet();
                case TOKENSERVICE_I_NFTS -> nftsCount.incrementAndGet();
                case TOKENSERVICE_I_PENDING_AIRDROPS -> airdropsCount.incrementAndGet();
                case TOKENSERVICE_I_STAKING_INFOS -> stakingInfoCount.incrementAndGet();
                case CONSENSUSSERVICE_I_TOPICS -> topicCount.incrementAndGet();
                case FILESERVICE_I_FILES -> fileCount.incrementAndGet();
                case ADDRESSBOOKSERVICE_I_NODES -> nodesCount.incrementAndGet();
                case SCHEDULESERVICE_I_SCHEDULES_BY_ID -> scheduleCount.incrementAndGet();
                case CONTRACTSERVICE_I_STORAGE -> contractStorageCount.incrementAndGet();
                case CONTRACTSERVICE_I_BYTECODE -> contractBytecodeCount.incrementAndGet();
                case CONTRACTSERVICE_I_EVM_HOOK_STATES -> hookCount.incrementAndGet();
                case CONTRACTSERVICE_I_EVM_HOOK_STORAGE -> evmHookStorageCount.incrementAndGet();
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        ValidationAssertions.requireNonNull(entityCounts, getName());

        final boolean ok = entityCounts.numAccounts() == accountCount.get()
                && entityCounts.numAliases() == aliasesCount.get()
                && entityCounts.numTokens() == tokenCount.get()
                && entityCounts.numTokenRelations() == tokenRelCount.get()
                && entityCounts.numNfts() == nftsCount.get()
                && entityCounts.numAirdrops() == airdropsCount.get()
                && entityCounts.numStakingInfos() == stakingInfoCount.get()
                && entityCounts.numTopics() == topicCount.get()
                && entityCounts.numFiles() == fileCount.get()
                && entityCounts.numNodes() == nodesCount.get()
                //      To be investigated - https://github.com/hiero-ledger/hiero-consensus-node/issues/20993
                //      && entityCounts.numSchedules() == scheduleCount.get()
                //      && entityCounts.numContractStorageSlots() == contractStorageCount.get()
                && entityCounts.numContractBytecodes() == contractBytecodeCount.get()
                && entityCounts.numHooks() == hookCount.get()
                && entityCounts.numEvmHookStorageSlots() == evmHookStorageCount.get();

        ValidationAssertions.requireTrue(
                ok,
                getName(),
                ("""
                        %s validation failed.
                        accounts exp=%d act=%d
                        aliases exp=%d act=%d
                        tokens exp=%d act=%d
                        tokenRels exp=%d act=%d
                        nfts exp=%d act=%d
                        airdrops exp=%d act=%d
                        stakingInfos exp=%d act=%d
                        topics exp=%d act=%d
                        files exp=%d act=%d
                        nodes exp=%d act=%d
                        contractBytecodes exp=%d act=%d
                        hooks exp=%d act=%d
                        lambdaStorageSlots exp=%d act=%d""")
                        .formatted(
                                getName(),
                                entityCounts.numAccounts(),
                                accountCount.get(),
                                entityCounts.numAliases(),
                                aliasesCount.get(),
                                entityCounts.numTokens(),
                                tokenCount.get(),
                                entityCounts.numTokenRelations(),
                                tokenRelCount.get(),
                                entityCounts.numNfts(),
                                nftsCount.get(),
                                entityCounts.numAirdrops(),
                                airdropsCount.get(),
                                entityCounts.numStakingInfos(),
                                stakingInfoCount.get(),
                                entityCounts.numTopics(),
                                topicCount.get(),
                                entityCounts.numFiles(),
                                fileCount.get(),
                                entityCounts.numNodes(),
                                nodesCount.get(),
                                entityCounts.numContractBytecodes(),
                                contractBytecodeCount.get(),
                                entityCounts.numHooks(),
                                hookCount.get(),
                                entityCounts.numEvmHookStorageSlots(),
                                evmHookStorageCount.get()));
    }
}
