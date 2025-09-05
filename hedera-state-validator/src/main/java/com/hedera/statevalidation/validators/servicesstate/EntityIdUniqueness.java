// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators.servicesstate;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_KEY;
import static com.hedera.node.app.service.consensus.impl.ConsensusServiceImpl.TOPICS_KEY;
import static com.hedera.node.app.service.contract.impl.schemas.V0490ContractSchema.BYTECODE_KEY;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.BLOBS_KEY;
import static com.hedera.node.app.service.schedule.impl.schemas.V0490ScheduleSchema.SCHEDULES_BY_ID_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.ACCOUNTS_KEY;
import static com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema.TOKENS_KEY;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.service.consensus.ConsensusService;
import com.hedera.node.app.service.contract.ContractService;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.schedule.ScheduleService;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.hedera.statevalidation.validators.ParallelProcessingUtil;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("entityIdUniqueness")
public class EntityIdUniqueness {

    private static final Logger log = LogManager.getLogger(EntityIdUniqueness.class);

    @Test
    void validate(DeserializedSignedState deserializedState, Report report)
            throws InterruptedException, ExecutionException {
        final MerkleNodeState servicesState =
                deserializedState.reservedSignedState().get().getState();
        final ReadableSingletonState<EntityNumber> entityIdSingleton =
                servicesState.getReadableStates(EntityIdService.NAME).getSingleton(ENTITY_ID_STATE_KEY);

        final long lastEntityIdNumber = entityIdSingleton.get().number();
        final AtomicInteger issuesFound = new AtomicInteger(0);

        final ReadableKVState<TokenID, Token> tokensState =
                servicesState.getReadableStates(TokenService.NAME).get(TOKENS_KEY);
        final ReadableKVState<AccountID, Account> accountState =
                servicesState.getReadableStates(TokenService.NAME).get(ACCOUNTS_KEY);
        final ReadableKVState<ContractID, Bytecode> smartContractState =
                servicesState.getReadableStates(ContractService.NAME).get(BYTECODE_KEY);
        final ReadableKVState<TopicID, Topic> topicState =
                servicesState.getReadableStates(ConsensusService.NAME).get(TOPICS_KEY);
        final ReadableKVState<FileID, File> fileState =
                servicesState.getReadableStates(FileService.NAME).get(BLOBS_KEY);
        final ReadableKVState<ScheduleID, Schedule> scheduleState =
                servicesState.getReadableStates(ScheduleService.NAME).get(SCHEDULES_BY_ID_KEY);

        ParallelProcessingUtil.processRange(0, lastEntityIdNumber, number -> {
                    int counter = 0;
                    // try {

                    final Token token = tokensState.get(new TokenID(0, 0, number));
                    if (token != null) {
                        counter++;
                    }

                    final Account account = accountState.get(
                            AccountID.newBuilder().accountNum(number).build());
                    if (account != null) {
                        counter++;
                    }

                    final Bytecode contract = smartContractState.get(
                            ContractID.newBuilder().contractNum(number).build());

                    if (contract != null) {
                        counter++;
                    }

                    final Topic topic =
                            topicState.get(TopicID.newBuilder().topicNum(number).build());

                    if (topic != null) {
                        counter++;
                    }

                    final File file =
                            fileState.get(FileID.newBuilder().fileNum(number).build());
                    if (file != null) {
                        counter++;
                    }

                    final Schedule schedule = scheduleState.get(new ScheduleID(0, 0, number));
                    if (schedule != null) {
                        counter++;
                    }

                    if (counter > 1) {
                        if (account != null && contract != null) {
                            // if it's a smart contract account, we expect it to have a contract with matching id
                            return;
                        }

                        final String errorMessage = String.format(
                                """
                                Entity ID %d is not unique, found %d entities.\s
                                 Token = %s, \
                                \s
                                 Account = %s,\s
                                 Contract = %s, \s
                                 Topic = %s,\s
                                 File = %s,\s
                                 Schedule = %s
                                """,
                                number, counter, token, account, contract, topic, file, schedule);
                        log.info(errorMessage);
                        issuesFound.incrementAndGet();
                    }
                })
                .get();

        log.info("Entity ID uniqueness validation completed. Issues found: " + issuesFound.get());
    }
}
