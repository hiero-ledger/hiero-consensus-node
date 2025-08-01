// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.MAP_DELETE;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.MAP_UPDATE;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_POP;
import static com.hedera.hapi.block.stream.output.StateChange.ChangeOperationOneOfType.QUEUE_PUSH;
import static com.swirlds.state.StateChangeListener.StateType.MAP;
import static com.swirlds.state.StateChangeListener.StateType.QUEUE;
import static com.swirlds.state.merkle.StateUtils.stateIdFor;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.HookId;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TimestampSeconds;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.consensus.Topic;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.contract.SlotKey;
import com.hedera.hapi.node.state.contract.SlotValue;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.hints.HintsPartyId;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.state.hints.PreprocessingVoteId;
import com.hedera.hapi.node.state.history.ConstructionNodeId;
import com.hedera.hapi.node.state.history.RecordedHistorySignature;
import com.hedera.hapi.node.state.hooks.EvmHookState;
import com.hedera.hapi.node.state.hooks.LambdaSlotKey;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduleList;
import com.hedera.hapi.node.state.schedule.ScheduledCounts;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.hapi.node.state.tss.TssMessageMapKey;
import com.hedera.hapi.node.state.tss.TssVoteMapKey;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.tss.TssMessageTransactionBody;
import com.hedera.hapi.services.auxiliary.tss.TssVoteTransactionBody;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ImmediateStateChangeListenerTest {
    private static final int STATE_ID = 1;
    private static final AccountID KEY = AccountID.newBuilder().accountNum(1234).build();
    private static final Account VALUE = Account.newBuilder().accountId(KEY).build();
    public static final ProtoBytes PROTO_BYTES = new ProtoBytes(Bytes.wrap(new byte[] {1, 2, 3}));
    private ImmediateStateChangeListener listener;

    @BeforeEach
    void setUp() {
        listener = new ImmediateStateChangeListener();
    }

    @Test
    void testGetStateChanges() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);

        List<StateChange> stateChanges = listener.getStateChanges();
        assertEquals(1, stateChanges.size());
    }

    @Test
    void testResetStateChanges() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);
        listener.reset(null);

        List<StateChange> stateChanges = listener.getStateChanges();
        assertTrue(stateChanges.isEmpty());
    }

    @Test
    void testMapUpdateChange() {
        listener.mapUpdateChange(STATE_ID, KEY, VALUE);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(MAP_UPDATE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(KEY, stateChange.mapUpdate().key().accountIdKey());
        assertEquals(VALUE, stateChange.mapUpdate().value().accountValue());
    }

    @Test
    void testMapDeleteChange() {
        listener.mapDeleteChange(STATE_ID, KEY);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(MAP_DELETE, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(KEY, stateChange.mapDelete().key().accountIdKey());
    }

    @ParameterizedTest
    @EnumSource(MapChangeKey.KeyChoiceOneOfType.class)
    void allMapChangeKeysAreValid(MapChangeKey.KeyChoiceOneOfType type) {
        final MapUpdateScenario<?, ?> scenario =
                switch (type) {
                    case UNSET -> null;

                    case ACCOUNT_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal(), AccountID.DEFAULT, Account.DEFAULT);

                    case TOKEN_RELATIONSHIP_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_TOKEN_RELATIONS.protoOrdinal(),
                                new EntityIDPair(AccountID.DEFAULT, TokenID.DEFAULT),
                                TokenRelation.DEFAULT);

                    case ENTITY_NUMBER_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_CONTRACT_BYTECODE.protoOrdinal(),
                                new EntityNumber(1L),
                                Bytecode.DEFAULT);

                    case FILE_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_FILES.protoOrdinal(), FileID.DEFAULT, File.DEFAULT);

                    case NFT_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_NFTS.protoOrdinal(), NftID.DEFAULT, Nft.DEFAULT);

                    case PROTO_BYTES_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_ALIASES.protoOrdinal(),
                                new ProtoBytes(Bytes.wrap("alias")),
                                new ProtoString("value"));

                    case PROTO_LONG_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_ENTITY_ID.protoOrdinal(),
                                new ProtoLong(123_456L),
                                new ProtoString("value"));

                    case PROTO_STRING_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_LEDGER_ID.protoOrdinal(),
                                new ProtoString("key"),
                                new ProtoString("value"));

                    case SCHEDULE_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_SCHEDULES_BY_ID.protoOrdinal(),
                                ScheduleID.DEFAULT,
                                Schedule.DEFAULT);

                    case SCHEDULED_ORDER_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_SCHEDULED_ORDERS.protoOrdinal(),
                                ScheduledOrder.DEFAULT,
                                ScheduleList.DEFAULT);

                    case TIMESTAMP_SECONDS_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_SCHEDULED_COUNTS.protoOrdinal(),
                                new TimestampSeconds(0L),
                                ScheduledCounts.DEFAULT);

                    case SLOT_KEY_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_CONTRACT_STORAGE.protoOrdinal(),
                                SlotKey.DEFAULT,
                                SlotValue.DEFAULT);

                    case CONTRACT_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_CONTRACT_BYTECODE.protoOrdinal(),
                                ContractID.DEFAULT,
                                Bytecode.DEFAULT);

                    case TOKEN_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_TOKENS.protoOrdinal(), TokenID.DEFAULT, Token.DEFAULT);

                    case TOPIC_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_TOPICS.protoOrdinal(), TopicID.DEFAULT, Topic.DEFAULT);

                    case PENDING_AIRDROP_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_PENDING_AIRDROPS.protoOrdinal(),
                                PendingAirdropId.DEFAULT,
                                AccountPendingAirdrop.DEFAULT);

                    case TSS_MESSAGE_MAP_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_TSS_MESSAGES.protoOrdinal(),
                                TssMessageMapKey.DEFAULT,
                                TssMessageTransactionBody.DEFAULT);

                    case TSS_VOTE_MAP_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_TSS_VOTES.protoOrdinal(),
                                TssVoteMapKey.DEFAULT,
                                TssVoteTransactionBody.DEFAULT);

                    case HINTS_PARTY_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_HINTS_KEY_SETS.protoOrdinal(),
                                HintsPartyId.DEFAULT,
                                HintsKeySet.DEFAULT);

                    case PREPROCESSING_VOTE_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_PREPROCESSING_VOTES.protoOrdinal(),
                                PreprocessingVoteId.DEFAULT,
                                PreprocessingVote.DEFAULT);

                    case NODE_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_NODES.protoOrdinal(), NodeId.DEFAULT, Node.DEFAULT);

                    case CONSTRUCTION_NODE_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_HISTORY_SIGNATURES.protoOrdinal(),
                                ConstructionNodeId.DEFAULT,
                                RecordedHistorySignature.DEFAULT);

                    case HOOK_ID_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_EVM_HOOK_STATES.protoOrdinal(),
                                HookId.DEFAULT,
                                EvmHookState.DEFAULT);

                    case LAMBDA_SLOT_KEY ->
                        new MapUpdateScenario<>(
                                StateIdentifier.STATE_ID_LAMBDA_STORAGE.protoOrdinal(),
                                LambdaSlotKey.DEFAULT,
                                SlotValue.DEFAULT);
                };
        if (scenario != null) {
            assertDoesNotThrow(() -> listener.mapUpdateChange(scenario.stateId, scenario.key, scenario.value));
        }
    }

    private record MapUpdateScenario<K, V>(int stateId, K key, V value) {}

    @Test
    void targetTypeIsMapAndQueue() {
        assertEquals(Set.of(MAP, QUEUE), listener.stateTypes());
    }

    @Test
    void understandsStateIds() {
        final var service = BlockStreamService.NAME;
        final var stateKey = V0560BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
        assertEquals(stateIdFor(service, stateKey), listener.stateIdFor(service, stateKey));
    }

    @Test
    void testQueuePushChange() {
        listener.queuePushChange(STATE_ID, PROTO_BYTES);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(QUEUE_PUSH, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
        assertEquals(PROTO_BYTES.value(), stateChange.queuePush().protoBytesElement());
    }

    @Test
    void testQueuePopChange() {
        listener.queuePopChange(STATE_ID);

        StateChange stateChange = listener.getStateChanges().getFirst();
        assertEquals(QUEUE_POP, stateChange.changeOperation().kind());
        assertEquals(STATE_ID, stateChange.stateId());
    }
}
