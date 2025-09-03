package com.hedera.statevalidation.validators.servicesstate;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.service.token.impl.TokenServiceImpl;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.parameterresolver.ReportResolver;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.reporting.Report;
import com.hedera.statevalidation.reporting.SlackReportGenerator;
import com.hedera.statevalidation.validators.ParallelProcessingUtil;
import com.swirlds.base.utility.Pair;
import com.swirlds.common.threading.manager.AdHocThreadManager;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapMigration;
import org.hiero.base.concurrent.interrupt.InterruptableConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.VALIDATOR_FORK_JOIN_POOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith({StateResolver.class, ReportResolver.class, SlackReportGenerator.class})
@Tag("entityIdUniqueness")
public class EntityIdUniqueness {

    @Test
    void validate(DeserializedSignedState deserializedState, Report report) throws InterruptedException, ExecutionException {
        final MerkleStateRoot servicesState = (MerkleStateRoot) deserializedState.reservedSignedState().get().getState();

        final AtomicReference<VirtualMap<OnDiskKey<TokenID>, OnDiskValue<Token>>> tokenVmRef = new AtomicReference<>();
        final AtomicReference<VirtualMap<OnDiskKey<AccountID>, OnDiskValue<Account>>> accountVmRef = new AtomicReference<>();
        final AtomicReference<VirtualMap<OnDiskKey<ContractID>, OnDiskValue<Bytecode>>> contractVmRef = new AtomicReference<>();
        final AtomicReference<VirtualMap<OnDiskKey<TopicID>, OnDiskValue<Topic>>> topicVmRef = new AtomicReference<>();
        final AtomicReference<VirtualMap<OnDiskKey<FileID>, OnDiskValue<File>>> fileVmRef = new AtomicReference<>();
        final AtomicReference<VirtualMap<OnDiskKey<ScheduleID>, OnDiskValue<Schedule>>> scheduleVm = new AtomicReference<>();
        SingletonNode<EntityNumber> entityIdSingleton = null;

        Map<String, Map<String, StateMetadata>> services = servicesState.getServices();
        final long tokenIdClassId = services.get("TokenService").get("TOKENS").onDiskKeyClassId();
        final long accountIdClassId = services.get("TokenService").get("ACCOUNTS").onDiskKeyClassId();
        final long contractIdClassId = services.get("ContractService").get("BYTECODE").onDiskKeyClassId();
        final long topicIdClassId = services.get("ConsensusService").get("TOPICS").onDiskKeyClassId();
        final long fileIdClassId = services.get("FileService").get("FILES").onDiskKeyClassId();
        final long scheduleIdClassId = services.get("ScheduleService").get("SCHEDULES_BY_ID").onDiskKeyClassId();

        for (int i = 0; i < servicesState.getNumberOfChildren(); i++) {
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap && virtualMap.getLabel().equals("TokenService.TOKENS")) {
                tokenVmRef.set((VirtualMap<OnDiskKey<TokenID>, OnDiskValue<Token>>) virtualMap);
            }
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap && virtualMap.getLabel().equals("TokenService.ACCOUNTS")) {
                accountVmRef.set((VirtualMap<OnDiskKey<AccountID>, OnDiskValue<Account>>) virtualMap);
            }
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap && virtualMap.getLabel().equals("ContractService.BYTECODE")) {
                contractVmRef.set((VirtualMap<OnDiskKey<ContractID>, OnDiskValue<Bytecode>>) virtualMap);
            }
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap && virtualMap.getLabel().equals("ConsensusService.TOPICS")) {
                topicVmRef.set((VirtualMap<OnDiskKey<TopicID>, OnDiskValue<Topic>>) virtualMap);
            }
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap && virtualMap.getLabel().equals("FileService.FILES")) {
                fileVmRef.set((VirtualMap<OnDiskKey<FileID>, OnDiskValue<File>>) virtualMap);
            }
            if (servicesState.getChild(i) instanceof VirtualMap<?, ?> virtualMap && virtualMap.getLabel().equals("ScheduleService.SCHEDULES_BY_ID")) {
                scheduleVm.set((VirtualMap<OnDiskKey<ScheduleID>, OnDiskValue<Schedule>>) virtualMap);
            }

            if (servicesState.getChild(i) instanceof SingletonNode<?> singletonNode && singletonNode.getLabel().equals("EntityIdService.ENTITY_ID")) {
                entityIdSingleton = (SingletonNode<EntityNumber>) singletonNode;
            }
        }

        assertNotNull(tokenVmRef);
        assertNotNull(accountVmRef);
        assertNotNull(contractVmRef);
        assertNotNull(topicVmRef);
        assertNotNull(fileVmRef);
        assertNotNull(scheduleVm);

        long lastEntityIdNumber = entityIdSingleton.getValue().number();
        AtomicInteger issuesFound = new AtomicInteger(0);

        ParallelProcessingUtil.processRange(0, lastEntityIdNumber, number -> {
            int counter = 0;
            OnDiskValue<Token> tokenValue = tokenVmRef.get().get(new OnDiskKey<>(tokenIdClassId, TokenID.PROTOBUF, new TokenID(0, 0, number)));
            Token token = null;
            if (tokenValue != null) {
                counter++;
                token = tokenValue.getValue();
            }

            OnDiskValue<Account> accountValue = accountVmRef.get().get(new OnDiskKey<>(accountIdClassId, AccountID.PROTOBUF,
                    AccountID.newBuilder().accountNum(number).build()));
            Account account = null;
            if (accountValue != null) {
                counter++;
                account = accountValue.getValue();
            }

            OnDiskValue<Bytecode> contractValue = contractVmRef.get().get(new OnDiskKey<>(contractIdClassId, ContractID.PROTOBUF,
                    ContractID.newBuilder().contractNum(number).build()));

            Bytecode contract = null;
            if (contractValue != null) {
                counter++;
                contract = contractValue.getValue();
            }

            OnDiskValue<Topic> topicValue = topicVmRef.get().get(new OnDiskKey<>(topicIdClassId, TopicID.PROTOBUF,
                    TopicID.newBuilder().topicNum(number).build()));
            Topic topic = null;
            if (topicValue != null) {
                counter++;
                topic = topicValue.getValue();
            }

            OnDiskValue<File> fileValue = fileVmRef.get().get(new OnDiskKey<>(fileIdClassId, FileID.PROTOBUF,
                    FileID.newBuilder().fileNum(number).build()));
            File file = null;
            if (fileValue != null) {
                counter++;
                file = fileValue.getValue() == null ? null : fileValue.getValue().copyBuilder().contents(Bytes.EMPTY).build();
            }

            OnDiskValue<Schedule> scheduleValue = scheduleVm.get().get(new OnDiskKey<>(scheduleIdClassId, ScheduleID.PROTOBUF, new ScheduleID(0, 0, number)));
            Schedule schedule = null;
            if (scheduleValue != null) {
                counter++;
                schedule = scheduleValue.getValue();
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
