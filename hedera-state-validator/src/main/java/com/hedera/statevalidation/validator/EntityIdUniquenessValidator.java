// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_STATE_ID;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_ID_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_STATE_ID;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_STATE_ID;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.statevalidation.util.StateUtils;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * @see LeafBytesValidator
 */
public class EntityIdUniquenessValidator implements LeafBytesValidator {

    private static final Logger log = LogManager.getLogger(EntityIdUniquenessValidator.class);

    public static final String ENTITY_ID_GROUP = "entityIds";
    public static final String ENTITY_ID_UNIQUENESS_NAME = "entityIdUniqueness";
    private static final long IMPERMISSIBLE_ENTITY_ID = -1L;

    private ReadableKVState<TokenID, Token> tokensState;
    private ReadableKVState<AccountID, Account> accountState;
    private ReadableKVState<ContractID, Bytecode> smartContractState;
    private ReadableKVState<TopicID, Topic> topicState;
    private ReadableKVState<FileID, File> fileState;
    private ReadableKVState<ScheduleID, Schedule> scheduleState;

    private final AtomicLong idCounter = new AtomicLong(0);
    private final AtomicLong issuesFound = new AtomicLong(0);

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
        return ENTITY_ID_UNIQUENESS_NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initialize(@NonNull final VirtualMapState state) {
        this.tokensState = Objects.requireNonNull(
                state.getReadableStates(TokenService.NAME).get(TOKENS_STATE_ID));
        this.accountState = Objects.requireNonNull(
                state.getReadableStates(TokenService.NAME).get(ACCOUNTS_STATE_ID));
        this.smartContractState = Objects.requireNonNull(
                state.getReadableStates(ContractService.NAME).get(BYTECODE_STATE_ID));
        this.topicState = Objects.requireNonNull(
                state.getReadableStates(ConsensusService.NAME).get(TOPICS_STATE_ID));
        this.fileState =
                Objects.requireNonNull(state.getReadableStates(FileService.NAME).get(FILES_STATE_ID));
        this.scheduleState = Objects.requireNonNull(
                state.getReadableStates(ScheduleService.NAME).get(SCHEDULES_BY_ID_STATE_ID));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void processLeafBytes(long dataLocation, @NonNull final VirtualLeafBytes<?> leafBytes) {
        long entityId = IMPERMISSIBLE_ENTITY_ID;

        try {
            final StateKey key = StateKey.PROTOBUF.parse(leafBytes.keyBytes());
            switch (key.key().kind()) {
                case TOKENSERVICE_I_TOKENS -> {
                    final TokenID tokenId = key.key().as();
                    entityId = tokenId.tokenNum();
                }
                case TOKENSERVICE_I_ACCOUNTS -> {
                    final AccountID accountId = key.key().as();
                    entityId = accountId.accountNumOrElse(IMPERMISSIBLE_ENTITY_ID);
                }
                case CONTRACTSERVICE_I_BYTECODE -> {
                    final ContractID contractId = key.key().as();
                    entityId = contractId.contractNumOrElse(IMPERMISSIBLE_ENTITY_ID);
                }
                case CONSENSUSSERVICE_I_TOPICS -> {
                    final TopicID topicId = key.key().as();
                    entityId = topicId.topicNum();
                }
                case FILESERVICE_I_FILES -> {
                    final FileID fileId = key.key().as();
                    entityId = fileId.fileNum();
                }
                case SCHEDULESERVICE_I_SCHEDULES_BY_ID -> {
                    final ScheduleID scheduleId = key.key().as();
                    entityId = scheduleId.scheduleNum();
                }
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }

        if (entityId != IMPERMISSIBLE_ENTITY_ID) {
            checkEntityUniqueness(entityId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void validate() {
        ValidationAssertions.requireEqual(0, issuesFound.get(), getName());
    }

    private void checkEntityUniqueness(long entityId) {
        // From time to time we need to reset cache to prevent OOM errors
        if (idCounter.incrementAndGet() % 100_000 == 0) {
            StateUtils.resetStateCache();
        }

        int counter = 0;
        final Token token = tokensState.get(new TokenID(0, 0, entityId));
        if (token != null) {
            counter++;
        }

        final Account account =
                accountState.get(AccountID.newBuilder().accountNum(entityId).build());
        if (account != null) {
            counter++;
        }

        final Bytecode contract = smartContractState.get(
                ContractID.newBuilder().contractNum(entityId).build());

        if (contract != null) {
            counter++;
        }

        final Topic topic =
                topicState.get(TopicID.newBuilder().topicNum(entityId).build());

        if (topic != null) {
            counter++;
        }

        final File file = fileState.get(FileID.newBuilder().fileNum(entityId).build());
        if (file != null) {
            counter++;
        }

        final Schedule schedule = scheduleState.get(new ScheduleID(0, 0, entityId));
        if (schedule != null) {
            counter++;
        }

        if (counter == 0) {
            final String errorMessage = String.format("No entity found for Entity ID %d", entityId);
            log.error(errorMessage);
            issuesFound.incrementAndGet();
        }
        if (counter > 1) {
            if ((account != null) && (contract != null) && (counter == 2)) {
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
            """, entityId, counter, token, account, contract, topic, file, schedule);
            log.error(errorMessage);
            issuesFound.incrementAndGet();
        }
    }
}
