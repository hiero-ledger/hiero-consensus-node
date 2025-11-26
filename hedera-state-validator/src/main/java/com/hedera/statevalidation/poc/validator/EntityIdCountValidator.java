// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator;

import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;

import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.statevalidation.poc.util.ValidationAssertions;
import com.hedera.statevalidation.poc.validator.api.LeafBytesValidator;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @see LeafBytesValidator
 */
public class EntityIdCountValidator implements LeafBytesValidator {

    public static final String ENTITY_ID_COUNT_TAG = "entityIdCount";

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
    private final AtomicLong lambdaStorageCount = new AtomicLong(0);

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull String getTag() {
        return ENTITY_ID_COUNT_TAG;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final MerkleNodeState state) {
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
                case CONTRACTSERVICE_I_LAMBDA_STORAGE -> lambdaStorageCount.incrementAndGet();
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
        ValidationAssertions.requireNonNull(entityCounts, getTag());
        ValidationAssertions.requireEqual(
                entityCounts.numAccounts(), accountCount.get(), getTag(), "Account count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numAliases(), aliasesCount.get(), getTag(), "Alias count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numTokens(), tokenCount.get(), getTag(), "Token count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numTokenRelations(), tokenRelCount.get(), getTag(), "Token relations count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numNfts(), nftsCount.get(), getTag(), "NFTs count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numAirdrops(), airdropsCount.get(), getTag(), "Airdrops count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numStakingInfos(), stakingInfoCount.get(), getTag(), "Staking infos count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numTopics(), topicCount.get(), getTag(), "Topic count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numFiles(), fileCount.get(), getTag(), "File count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numNodes(), nodesCount.get(), getTag(), "Nodes count is unexpected");
        //      To be investigated - https://github.com/hiero-ledger/hiero-consensus-node/issues/20993
        //      ValidationAssertions.requireEqual(entityCounts.numSchedules(), scheduleCount.get(), getTag(),
        // "Schedule count is unexpected");
        //      ValidationAssertions.requireEqual(
        //                entityCounts.numContractStorageSlots(),
        //                contractStorageCount.get(),
        //                getTag(),
        //                "Contract storage count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numContractBytecodes(),
                contractBytecodeCount.get(),
                getTag(),
                "Contract count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numHooks(), hookCount.get(), getTag(), "Hook count is unexpected");
        ValidationAssertions.requireEqual(
                entityCounts.numLambdaStorageSlots(),
                lambdaStorageCount.get(),
                getTag(),
                "Lambda slot count is unexpected");
    }
}
