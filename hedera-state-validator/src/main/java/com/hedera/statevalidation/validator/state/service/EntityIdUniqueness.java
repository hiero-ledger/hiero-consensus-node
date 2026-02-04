// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.state.service;

import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_ID_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.statevalidation.report.SlackReportGenerator;
import com.hedera.statevalidation.util.ParallelProcessingUtils;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({SlackReportGenerator.class})
@Tag("entityIds")
public class EntityIdUniqueness {

    private static final Logger log = LogManager.getLogger(EntityIdUniqueness.class);

    @Test
    void validateEntityIds() throws InterruptedException, ExecutionException {
        final ReadableSingletonState<EntityNumber> entityIdSingleton = StateUtils.getDefaultState()
                .getReadableStates(EntityIdService.NAME)
                .getSingleton(ENTITY_ID_STATE_ID);
        final AtomicLong idCounter = new AtomicLong(0);

        final long lastEntityIdNumber = entityIdSingleton.get().number();
        final AtomicInteger issuesFound = new AtomicInteger(0);

        ParallelProcessingUtils.processRange(0, lastEntityIdNumber, number -> {
                    if (idCounter.incrementAndGet() % 100_000 == 0) {
                        // from time to time we need to create copies to limit cache growth and prevent OOM errors
                        StateUtils.resetStateCache();
                    }

                    final MerkleNodeState state = StateUtils.getDefaultState();

                    int counter = 0;
                    final Token token = getTokensState(state).get(new TokenID(0, 0, number));
                    if (token != null) {
                        counter++;
                    }

                    final Account account = getAccountState(state)
                            .get(AccountID.newBuilder().accountNum(number).build());
                    if (account != null) {
                        counter++;
                    }

                    final Bytecode contract = getSmartContractState(state)
                            .get(ContractID.newBuilder().contractNum(number).build());

                    if (contract != null) {
                        counter++;
                    }

                    final Topic topic = getTopicState(state)
                            .get(TopicID.newBuilder().topicNum(number).build());

                    if (topic != null) {
                        counter++;
                    }

                    final File file = getFileState(state)
                            .get(FileID.newBuilder().fileNum(number).build());
                    if (file != null) {
                        counter++;
                    }

                    final Schedule schedule = getScheduleState(state).get(new ScheduleID(0, 0, number));
                    if (schedule != null) {
                        counter++;
                    }

                    if (counter > 1) {
                        if (account != null && contract != null) {
                            // if it's a smart contract account, we expect it to have a contract with matching id
                            return;
                        }

                        final String errorMessage =
                                String.format("""
                        Entity ID %d is not unique, found %d entities.\s
                         Token = %s, \
                        \s
                         Account = %s,\s
                         Contract = %s, \s
                         Topic = %s,\s
                         File = %s,\s
                         Schedule = %s
                        """, number, counter, token, account, contract, topic, file, schedule);
                        log.info(errorMessage);
                        issuesFound.incrementAndGet();
                    }
                })
                .get();

        log.info("Entity ID uniqueness validation completed. Issues found: " + issuesFound.get());
        assertEquals(0, issuesFound.get());
    }

    private static @NonNull ReadableKVState<ScheduleID, Schedule> getScheduleState(MerkleNodeState state) {
        return state.getReadableStates(ScheduleService.NAME).get(SCHEDULES_BY_ID_STATE_ID);
    }

    private static @NonNull ReadableKVState<FileID, File> getFileState(MerkleNodeState state) {
        return state.getReadableStates(FileService.NAME).get(FILES_STATE_ID);
    }

    private static @NonNull ReadableKVState<TopicID, Topic> getTopicState(MerkleNodeState state) {
        return state.getReadableStates(ConsensusService.NAME).get(TOPICS_STATE_ID);
    }

    private static @NonNull ReadableKVState<ContractID, Bytecode> getSmartContractState(MerkleNodeState state) {
        return state.getReadableStates(ContractService.NAME).get(BYTECODE_STATE_ID);
    }

    private static @NonNull ReadableKVState<AccountID, Account> getAccountState(MerkleNodeState state) {
        return state.getReadableStates(TokenService.NAME).get(ACCOUNTS_STATE_ID);
    }

    private static @NonNull ReadableKVState<TokenID, Token> getTokensState(MerkleNodeState state) {
        return state.getReadableStates(TokenService.NAME).get(TOKENS_STATE_ID);
    }

    @Test
    void validateIdCounts() throws InterruptedException, ExecutionException {

        final MerkleNodeState servicesState = StateUtils.getDefaultState();
        final AtomicLong pathCounter = new AtomicLong(0);

        final ReadableSingletonState<EntityCounts> entityIdSingleton =
                servicesState.getReadableStates(EntityIdService.NAME).getSingleton(ENTITY_COUNTS_STATE_ID);

        final EntityCounts entityCounts = entityIdSingleton.get();
        final VirtualMapMetadata metadata = ((VirtualMap) servicesState.getRoot()).getMetadata();

        final AtomicLong accountCount = new AtomicLong(0);
        final AtomicLong aliasesCount = new AtomicLong(0);
        final AtomicLong tokenCount = new AtomicLong(0);
        final AtomicLong tokenRelCount = new AtomicLong(0);
        final AtomicLong nftsCount = new AtomicLong(0);
        final AtomicLong airdropsCount = new AtomicLong(0);
        final AtomicLong stakingInfoCount = new AtomicLong(0);
        final AtomicLong topicCount = new AtomicLong(0);
        final AtomicLong fileCount = new AtomicLong(0);
        final AtomicLong nodesCount = new AtomicLong(0);
        final AtomicLong scheduleCount = new AtomicLong(0);
        final AtomicLong contractStorageCount = new AtomicLong(0);
        final AtomicLong contractBytecodeCount = new AtomicLong(0);
        final AtomicLong hookCount = new AtomicLong(0);
        final AtomicLong labmbdaStorageCount = new AtomicLong(0);
        final AtomicLong evmHookStorageCount = new AtomicLong(0);

        ParallelProcessingUtils.processRange(metadata.getFirstLeafPath(), metadata.getLastLeafPath(), number -> {
                    // from time to time we need to create copies to limit cache growth and prevent OOM errors
                    if (pathCounter.incrementAndGet() % 100_000 == 0) {
                        StateUtils.resetStateCache();
                    }

                    final VirtualMap vm =
                            (VirtualMap) StateUtils.getDefaultState().getRoot();

                    VirtualLeafBytes leafRecord = vm.getRecords().findLeafRecord(number);
                    try {
                        StateKey key = StateKey.PROTOBUF.parse(leafRecord.keyBytes());
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
                })
                .get();

        assertEquals(entityCounts.numAccounts(), accountCount.get(), "Account count is unexpected");
        assertEquals(entityCounts.numAliases(), aliasesCount.get(), "Alias count is unexpected");
        assertEquals(entityCounts.numTokens(), tokenCount.get(), "Token count is unexpected");
        assertEquals(entityCounts.numTokenRelations(), tokenRelCount.get(), "Token relations count is unexpected");
        assertEquals(entityCounts.numNfts(), nftsCount.get(), "NFTs count is unexpected");
        assertEquals(entityCounts.numAirdrops(), airdropsCount.get(), "Airdrops count is unexpected");
        assertEquals(entityCounts.numStakingInfos(), stakingInfoCount.get(), "Staking infos count is unexpected");
        assertEquals(entityCounts.numTopics(), topicCount.get(), "Topic count is unexpected");
        assertEquals(entityCounts.numFiles(), fileCount.get(), "File count is unexpected");
        assertEquals(entityCounts.numNodes(), nodesCount.get(), "Nodes count is unexpected");
        //      To be investigated - https://github.com/hiero-ledger/hiero-consensus-node/issues/20993
        //      assertEquals(entityCounts.numSchedules(), scheduleCount.get(), "Schedule count is unexpected");
        //      assertEquals(
        //                entityCounts.numContractStorageSlots(),
        //                contractStorageCount.get(),
        //                "Contract storage count is unexpected");
        assertEquals(entityCounts.numContractBytecodes(), contractBytecodeCount.get(), "Contract count is unexpected");
        assertEquals(entityCounts.numHooks(), hookCount.get(), "Hook count is unexpected");
        assertEquals(
                entityCounts.numEvmHookStorageSlots(),
                evmHookStorageCount.get(),
                "EVM hook storage slot count is unexpected");
    }
}
