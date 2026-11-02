// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.corrections;

import static com.hedera.hapi.node.base.ResponseCodeEnum.EMPTY_STATE_ERRATA;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ERRATA_STATE_CHANGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.NewStateChange;
import com.hedera.hapi.block.stream.output.QueuePopChange;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.network.StateErrataTransactionBody;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.corrections.StateDefinitionLookup.ServiceStateDefinition;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateErrataHandlerTest {
    private static final String SERVICE_NAME = "TestService";
    private static final int ACCOUNTS_ID = 2;
    private static final int COUNTS_ID = 41;
    private static final int QUEUE_ID = 4242;
    private static final int UNKNOWN_ID = 99_999;

    private static final AccountID EXISTING_ID =
            AccountID.newBuilder().accountNum(1001).build();
    private static final AccountID NEW_ID =
            AccountID.newBuilder().accountNum(1002).build();
    private static final Account EXISTING =
            Account.newBuilder().accountId(EXISTING_ID).memo("wrong").build();

    private static final StateDefinitionLookup LOOKUP = stateId -> switch (stateId) {
        case ACCOUNTS_ID ->
            new ServiceStateDefinition(
                    SERVICE_NAME,
                    StateDefinition.keyValue(ACCOUNTS_ID, "ACCOUNTS", AccountID.PROTOBUF, Account.PROTOBUF));
        case COUNTS_ID ->
            new ServiceStateDefinition(
                    SERVICE_NAME, StateDefinition.singleton(COUNTS_ID, "ENTITY_COUNTS", EntityCounts.PROTOBUF));
        case QUEUE_ID ->
            new ServiceStateDefinition(
                    SERVICE_NAME, StateDefinition.queue(QUEUE_ID, "TEST_QUEUE", ProtoBytes.PROTOBUF));
        default -> null;
    };

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private SavepointStackImpl stack;

    private MapWritableKVState<AccountID, Account> accounts;
    private final AtomicReference<EntityCounts> counts = new AtomicReference<>(EntityCounts.DEFAULT);
    private final Queue<ProtoBytes> queue = new ArrayDeque<>();
    private MapWritableStates writableStates;

    private final StateErrataHandler subject = new StateErrataHandler(LOOKUP);

    @BeforeEach
    void setUp() {
        accounts = MapWritableKVState.<AccountID, Account>builder(ACCOUNTS_ID, "ACCOUNTS")
                .value(EXISTING_ID, EXISTING)
                .build();
        queue.add(new ProtoBytes(Bytes.wrap("first")));
        writableStates = MapWritableStates.builder()
                .state(accounts)
                .state(new FunctionWritableSingletonState<>(COUNTS_ID, "ENTITY_COUNTS", counts::get, counts::set))
                .state(new ListWritableQueueState<>(QUEUE_ID, "TEST_QUEUE", queue))
                .build();
    }

    @Test
    void pureChecksRejectEmptyErrata() {
        assertPureChecksFail(EMPTY_STATE_ERRATA);
    }

    @Test
    void pureChecksRejectUnparseableUnsupportedOrIncompleteChanges() {
        assertPureChecksFail(INVALID_ERRATA_STATE_CHANGE, Bytes.wrap(new byte[] {(byte) 0xFF, 0x01}));
        assertPureChecksFail(
                INVALID_ERRATA_STATE_CHANGE,
                serialize(StateChange.newBuilder()
                        .stateId(ACCOUNTS_ID)
                        .stateAdd(NewStateChange.DEFAULT)
                        .build()));
        assertPureChecksFail(
                INVALID_ERRATA_STATE_CHANGE,
                serialize(StateChange.newBuilder()
                        .stateId(ACCOUNTS_ID)
                        .mapUpdate(MapUpdateChange.newBuilder().key(accountKey(NEW_ID)))
                        .build()));
        assertPureChecksFail(
                INVALID_ERRATA_STATE_CHANGE,
                serialize(StateChange.newBuilder().stateId(ACCOUNTS_ID).build()));
    }

    @Test
    void pureChecksAcceptSupportedChanges() {
        given(pureChecksContext.body())
                .willReturn(body(
                        updateAccount(EXISTING.copyBuilder().memo("right").build()),
                        deleteAccount(NEW_ID),
                        updateCounts(EntityCounts.newBuilder().numAccounts(1).build()),
                        pushQueue("second"),
                        popQueue()));

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void appliesEverySupportedKindOfChangeInOrder() {
        givenHandleOf(
                updateAccount(EXISTING.copyBuilder().memo("right").build()),
                updateAccount(Account.newBuilder().accountId(NEW_ID).build()),
                deleteAccount(NEW_ID),
                updateCounts(EntityCounts.newBuilder().numAccounts(7).build()),
                popQueue(),
                pushQueue("second"));

        subject.handle(handleContext);
        writableStates.commit();

        assertThat(accounts.getBackingStore())
                .containsExactlyEntriesOf(java.util.Map.of(
                        EXISTING_ID, EXISTING.copyBuilder().memo("right").build()));
        assertThat(counts.get().numAccounts()).isEqualTo(7);
        assertThat(queue).containsExactly(new ProtoBytes(Bytes.wrap("second")));
    }

    @Test
    void rejectsUnknownStates() {
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(UNKNOWN_ID)
                .mapDelete(new MapDeleteChange(accountKey(EXISTING_ID)))
                .build());
    }

    @Test
    void rejectsOperationsNotMatchingTheStateKind() {
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(COUNTS_ID)
                .mapDelete(new MapDeleteChange(accountKey(EXISTING_ID)))
                .build());
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(ACCOUNTS_ID)
                .queuePop(QueuePopChange.DEFAULT)
                .build());
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(QUEUE_ID)
                .singletonUpdate(SingletonUpdateChange.newBuilder()
                        .entityCountsValue(EntityCounts.DEFAULT)
                        .build())
                .build());
    }

    @Test
    void rejectsKeysAndValuesOfTheWrongType() {
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(ACCOUNTS_ID)
                .mapDelete(new MapDeleteChange(
                        MapChangeKey.newBuilder().tokenIdKey(TokenID.DEFAULT).build()))
                .build());
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(ACCOUNTS_ID)
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(accountKey(EXISTING_ID))
                        .value(MapChangeValue.newBuilder().tokenValue(Token.DEFAULT)))
                .build());
        assertHandleFailsWithoutApplying(StateChange.newBuilder()
                .stateId(COUNTS_ID)
                .singletonUpdate(SingletonUpdateChange.newBuilder()
                        .bytesValue(Bytes.wrap("x"))
                        .build())
                .build());
    }

    @Test
    void appliesNothingIfAnyChangeIsInvalid() {
        assertHandleFailsWithoutApplying(
                updateAccount(EXISTING.copyBuilder().memo("right").build()),
                StateChange.newBuilder()
                        .stateId(UNKNOWN_ID)
                        .mapDelete(new MapDeleteChange(accountKey(EXISTING_ID)))
                        .build());
        assertThat(accounts.getBackingStore()).containsEntry(EXISTING_ID, EXISTING);
    }

    private void givenHandleOf(final StateChange... changes) {
        given(handleContext.body()).willReturn(body(changes));
        given(handleContext.savepointStack()).willReturn(stack);
        given(stack.getWritableStates(anyString())).willReturn(writableStates);
    }

    private void assertHandleFailsWithoutApplying(final StateChange... changes) {
        given(handleContext.body()).willReturn(body(changes));
        final var e = assertThrows(HandleException.class, () -> subject.handle(handleContext));
        assertThat(e.getStatus()).isEqualTo(INVALID_ERRATA_STATE_CHANGE);
        verify(stack, never()).getWritableStates(anyString());
    }

    private void assertPureChecksFail(final ResponseCodeEnum expected, final Bytes... serializedChanges) {
        given(pureChecksContext.body())
                .willReturn(TransactionBody.newBuilder()
                        .stateErrata(StateErrataTransactionBody.newBuilder()
                                .stateChanges(Arrays.asList(serializedChanges))
                                .build())
                        .build());
        final var e = assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
        assertThat(e.responseCode()).isEqualTo(expected);
    }

    private static TransactionBody body(final StateChange... changes) {
        return TransactionBody.newBuilder()
                .stateErrata(StateErrataTransactionBody.newBuilder()
                        .stateChanges(Arrays.stream(changes)
                                .map(StateErrataHandlerTest::serialize)
                                .toList())
                        .build())
                .build();
    }

    private static Bytes serialize(final StateChange change) {
        return StateChange.PROTOBUF.toBytes(change);
    }

    private static MapChangeKey accountKey(final AccountID id) {
        return MapChangeKey.newBuilder().accountIdKey(id).build();
    }

    private static StateChange updateAccount(final Account account) {
        return StateChange.newBuilder()
                .stateId(ACCOUNTS_ID)
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(accountKey(account.accountIdOrThrow()))
                        .value(MapChangeValue.newBuilder().accountValue(account)))
                .build();
    }

    private static StateChange deleteAccount(final AccountID id) {
        return StateChange.newBuilder()
                .stateId(ACCOUNTS_ID)
                .mapDelete(new MapDeleteChange(accountKey(id)))
                .build();
    }

    private static StateChange updateCounts(final EntityCounts entityCounts) {
        return StateChange.newBuilder()
                .stateId(COUNTS_ID)
                .singletonUpdate(SingletonUpdateChange.newBuilder()
                        .entityCountsValue(entityCounts)
                        .build())
                .build();
    }

    private static StateChange pushQueue(final String value) {
        return StateChange.newBuilder()
                .stateId(QUEUE_ID)
                .queuePush(QueuePushChange.newBuilder()
                        .protoBytesElement(Bytes.wrap(value))
                        .build())
                .build();
    }

    private static StateChange popQueue() {
        return StateChange.newBuilder()
                .stateId(QUEUE_ID)
                .queuePop(QueuePopChange.DEFAULT)
                .build();
    }
}
