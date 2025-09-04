package com.hedera.statevalidation.validators.servicesstate;

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
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.hedera.statevalidation.validators.ParallelProcessingUtil;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.virtualmap.VirtualMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("entityIdUniqueness")
public class EntityIdUniqueness {

    @Test
    void validate(DeserializedSignedState deserializedState, Report report) throws InterruptedException, ExecutionException {
        final MerkleNodeState servicesState = deserializedState.reservedSignedState().get().getState();
        final VirtualMap vm = (VirtualMap) servicesState.getRoot();

        ReadableSingletonState<EntityNumber> entityIdSingleton =
                servicesState.getReadableStates("EntityIdService").getSingleton("ENTITY_ID");

        long lastEntityIdNumber = entityIdSingleton.get().number();
        AtomicInteger issuesFound = new AtomicInteger(0);

        final ReadableKVState<TokenID, Token> tokensState = servicesState.getReadableStates("TokenService").get("TOKENS");
        final ReadableKVState<AccountID, Account> accountState = servicesState.getReadableStates("TokenService").get("ACCOUNTS");
        final ReadableKVState<ContractID, Bytecode> smartContractState = servicesState.getReadableStates("ContractService").get("BYTECODE");
        final ReadableKVState<TopicID, Topic> topicState = servicesState.getReadableStates("ConsensusService").get("TOPICS");
        final ReadableKVState<FileID, File> fileState = servicesState.getReadableStates("FileService").get("FILES");
        final ReadableKVState<ScheduleID, Schedule> scheduleState = servicesState.getReadableStates("ScheduleService").get("SCHEDULES_BY_ID");

        ParallelProcessingUtil.processRange(0, lastEntityIdNumber, number -> {
            int counter = 0;
            //try {

            Token token = tokensState.get(new TokenID(0, 0, number));
            if (token != null) {
                counter++;
            }

            Account account = accountState.get(AccountID.newBuilder().accountNum(number).build());
            if (account != null) {
                counter++;
            }

            Bytecode contract = smartContractState.get(ContractID.newBuilder().contractNum(number).build());

            if (contract != null) {
                counter++;
            }

            Topic topic = topicState.get(TopicID.newBuilder().topicNum(number).build());

            if (topic != null) {
                counter++;
            }

            File file = fileState.get(FileID.newBuilder().fileNum(number).build());
            if (file != null) {
                counter++;
            }

            Schedule schedule = scheduleState.get(new ScheduleID(0, 0, number));
            if (schedule != null) {
                counter++;
            }

            if (counter > 1) {
                    if (account != null && contract != null) {
                        // if it's a smart contract account, we expect it to have a contract with matching id
                        return;
                    }

                final String errorMessage = String.format("""
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
                System.out.println(errorMessage);
                issuesFound.incrementAndGet();
            }
        }).get();

        System.out.println("Entity ID uniqueness validation completed. Issues found: " + issuesFound.get());
    }
}
