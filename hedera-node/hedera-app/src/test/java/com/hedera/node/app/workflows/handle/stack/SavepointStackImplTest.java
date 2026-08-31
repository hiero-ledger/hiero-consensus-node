// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECURSIVE_SCHEDULING_LIMIT_REACHED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.workflows.handle.record.TraceDataSizeLimiter;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapReadableStates;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import org.assertj.core.api.Condition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SavepointStackImplTest extends StateTestBase {

    private static final String FOOD_SERVICE = "FOOD_SERVICE";
    private static final long BLOCK_NUMBER = 123L;
    private static final AccountID PAYER_ID =
            AccountID.newBuilder().accountNum(666L).build();
    private static final Timestamp VALID_START = new Timestamp(1_234_567L, 890);

    private final Map<ProtoBytes, ProtoBytes> BASE_DATA = Map.of(
            A_KEY, APPLE,
            B_KEY, BANANA,
            C_KEY, CHERRY,
            D_KEY, DATE,
            E_KEY, EGGPLANT,
            F_KEY, FIG,
            G_KEY, GRAPE);

    @Mock(strictness = LENIENT)
    private State baseState;

    @Mock
    private SavepointStackImpl parent;

    @Mock
    private Savepoint savepoint;

    @Mock
    private BoundaryStateChangeListener roundStateChangeListener;

    @Mock
    private ImmediateStateChangeListener immediateStateChangeListener;

    private StreamMode streamMode;

    @BeforeEach
    void setup() {
        final var baseKVState = new MapWritableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, new HashMap<>(BASE_DATA));
        final var writableStates =
                MapWritableStates.builder().state(baseKVState).build();
        when(baseState.getReadableStates(FOOD_SERVICE)).thenReturn(writableStates);
        when(baseState.getWritableStates(FOOD_SERVICE)).thenReturn(writableStates);
        final var config = new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
        streamMode = config.getConfigData(BlockStreamConfig.class).streamMode();
    }

    @Test
    void parentGivesIdsUntilLastAllowed() {
        final var vanillaBaseId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .build();
        final var subject = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.BOTH,
                TraceDataSizeLimiter.NO_LIMIT);
        subject.getBaseBuilder(StreamBuilder.class).transactionID(vanillaBaseId);

        final var firstPresetId = subject.nextPresetTxnId(false);
        final var secondPresetId = subject.nextPresetTxnId(true);
        assertThatThrownBy(() -> subject.nextPresetTxnId(false))
                .isInstanceOf(HandleException.class)
                .hasMessage(NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION.protoName());
        assertThat(firstPresetId)
                .isEqualTo(vanillaBaseId.copyBuilder().nonce(53).build());
        assertThat(secondPresetId)
                .isEqualTo(vanillaBaseId.copyBuilder().nonce(2 * 53).build());
    }

    @Test
    void childReturnsPresetIdFromParent() {
        final var vanillaBaseId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .build();
        final var parent = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.BOTH,
                TraceDataSizeLimiter.NO_LIMIT);
        parent.getBaseBuilder(StreamBuilder.class).transactionID(vanillaBaseId);
        final var subject = SavepointStackImpl.newChildStack(
                parent, REVERSIBLE, SCHEDULED, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.BOTH);

        final var presetId = subject.nextPresetTxnId(false);
        assertThat(presetId).isEqualTo(vanillaBaseId.copyBuilder().nonce(53).build());
    }

    @Test
    void parentDetectsRecursionLimit() {
        final var vanillaBaseId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .scheduled(true)
                .nonce(-53)
                .build();
        final var subject = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.BOTH,
                TraceDataSizeLimiter.NO_LIMIT);
        subject.getBaseBuilder(StreamBuilder.class).transactionID(vanillaBaseId);
        assertThatThrownBy(() -> subject.nextPresetTxnId(false))
                .isInstanceOf(HandleException.class)
                .hasMessage(RECURSIVE_SCHEDULING_LIMIT_REACHED.protoName());
    }

    @Test
    void topLevelPermitsStakingRewards() {
        final var subject = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.BOTH,
                TraceDataSizeLimiter.NO_LIMIT);
        assertThat(subject.permitsStakingRewards()).isTrue();
    }

    @Test
    void childDoesNotPermitStakingRewardsIfNotScheduled() {
        given(parent.peek()).willReturn(savepoint);
        given(savepoint.followingCapacity()).willReturn(123);
        final var subject = SavepointStackImpl.newChildStack(
                parent,
                REVERSIBLE,
                HandleContext.TransactionCategory.CHILD,
                NOOP_SIGNED_TX_CUSTOMIZER,
                StreamMode.BOTH);
        assertThat(subject.permitsStakingRewards()).isFalse();
    }

    @Test
    void childDoesNotPermitStakingRewardsIfNotScheduledByUser() {
        given(parent.peek()).willReturn(savepoint);
        given(savepoint.followingCapacity()).willReturn(123);
        given(parent.txnCategory()).willReturn(HandleContext.TransactionCategory.CHILD);
        final var subject = SavepointStackImpl.newChildStack(
                parent, REVERSIBLE, SCHEDULED, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.BOTH);
        assertThat(subject.permitsStakingRewards()).isFalse();
    }

    @Test
    void scheduledTopLevelIfSchedulingParentIsUser() {
        given(parent.peek()).willReturn(savepoint);
        given(savepoint.followingCapacity()).willReturn(123);
        given(parent.txnCategory()).willReturn(HandleContext.TransactionCategory.USER);
        final var subject = SavepointStackImpl.newChildStack(
                parent, REVERSIBLE, SCHEDULED, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.BOTH);
        assertThat(subject.permitsStakingRewards()).isTrue();
    }

    @Test
    void rootHasPrecedingCapacityUntilLimitReached() {
        final var subject = SavepointStackImpl.newRootStack(
                baseState,
                2,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                streamMode,
                TraceDataSizeLimiter.NO_LIMIT);
        assertThat(subject.rootHasPrecedingCapacity()).isTrue();

        subject.createIrreversiblePrecedingBuilder();
        assertThat(subject.rootHasPrecedingCapacity()).isTrue();

        subject.createIrreversiblePrecedingBuilder();
        assertThat(subject.rootHasPrecedingCapacity()).isFalse();
    }

    @Test
    void rootHasPrecedingCapacityThrowsForChildStack() {
        final var root = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                streamMode,
                TraceDataSizeLimiter.NO_LIMIT);
        final var child = SavepointStackImpl.newChildStack(
                root, REVERSIBLE, HandleContext.TransactionCategory.CHILD, NOOP_SIGNED_TX_CUSTOMIZER, streamMode);

        assertThatThrownBy(child::rootHasPrecedingCapacity).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testConstructor() {
        // when
        final var stack = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                streamMode,
                TraceDataSizeLimiter.NO_LIMIT);

        // then
        assertThat(stack.depth()).isEqualTo(1);
        assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(stack.getReadableStates(FOOD_SERVICE)).isSameAs(stack.getReadableStates(FOOD_SERVICE));
        assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(stack.getWritableStates(FOOD_SERVICE));
        final var writable = stack.getWritableStates(FOOD_SERVICE);
        assertThat(writable.get(FRUIT_STATE_ID)).isSameAs(writable.get(FRUIT_STATE_ID));
    }

    @SuppressWarnings("ConstantConditions")
    @Test
    void testConstructorWithInvalidParameters() {
        assertThatThrownBy(() -> SavepointStackImpl.newRootStack(
                        null,
                        3,
                        50,
                        roundStateChangeListener,
                        immediateStateChangeListener,
                        streamMode,
                        TraceDataSizeLimiter.NO_LIMIT))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testModification() {
        // given
        final var stack = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                streamMode,
                TraceDataSizeLimiter.NO_LIMIT);
        final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
        final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);

        // when
        writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
        stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);

        // then
        assertThat(stack.depth()).isEqualTo(1);
        final var newData = new HashMap<>(BASE_DATA);
        newData.put(A_KEY, ACAI);
        newData.put(B_KEY, BLUEBERRY);
        assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
        assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
        assertThat(readableStatesStack).has(content(newData));
        assertThat(writableStatesStack).has(content(newData));
        assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
        assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
    }

    @Test
    void buildHandleOutputLeavesReceiptBlockNumberUnsetInRecordsMode() {
        final var txnId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .build();
        final var stack = SavepointStackImpl.newRootStack(
                baseState,
                3,
                50,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.RECORDS,
                TraceDataSizeLimiter.NO_LIMIT);
        stack.getBaseBuilder(StreamBuilder.class)
                .transactionID(txnId)
                .signedTx(SignedTransaction.DEFAULT)
                .status(SUCCESS)
                .exchangeRate(ExchangeRateSet.DEFAULT);
        stack.commitFullStack();

        final var handleOutput = stack.buildHandleOutput(
                Instant.ofEpochSecond(VALID_START.seconds(), VALID_START.nanos()),
                ExchangeRateSet.DEFAULT,
                BLOCK_NUMBER);
        final var records = new java.util.ArrayList<com.hedera.hapi.node.transaction.TransactionRecord>();
        handleOutput.recordSourceOrThrow().forEachTxnRecord(records::add);

        assertThat(records).singleElement().satisfies(record -> assertThat(
                        record.receiptOrThrow().blockNumber())
                .isNull());
    }

    @Nested
    @DisplayName("Tests for adding new savepoints to the stack")
    class SavepointTests {
        @Test
        void testInitialCreatedSavepoint() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);

            // when
            stack.createSavepoint();

            // then
            assertThat(stack.depth()).isEqualTo(2);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(BASE_DATA));
            assertThat(writableStatesStack).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        }

        @Test
        void testModifiedSavepoint() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);

            // when
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);

            // then
            assertThat(stack.depth()).isEqualTo(2);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testMultipleSavepoints() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);

            // when
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(E_KEY, ELDERBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(F_KEY, FEIJOA);

            // then
            assertThat(stack.depth()).isEqualTo(3);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            newData.put(E_KEY, ELDERBERRY);
            newData.put(F_KEY, FEIJOA);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
        }
    }

    @Nested
    @DisplayName("Test for committing savepoints")
    class CommitTests {
        @Test
        void testCommittedSavepoint() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);

            // when
            stack.commit();

            // then
            assertThat(stack.depth()).isEqualTo(1);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testModificationsAfterCommit() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.commit();

            // when
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);

            // then
            assertThat(stack.depth()).isEqualTo(1);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testNewSavepointAfterCommit() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.commit();

            // when
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);

            // then
            assertThat(stack.depth()).isEqualTo(2);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testMultipleCommits() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(E_KEY, ELDERBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(F_KEY, FEIJOA);

            // when
            stack.commit();
            stack.commit();

            // then
            assertThat(stack.depth()).isEqualTo(2);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            newData.put(E_KEY, ELDERBERRY);
            newData.put(F_KEY, FEIJOA);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testCommitInitialStackFails() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);

            // then
            assertThatThrownBy(stack::commit).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void testTooManyCommitsFail() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            stack.createSavepoint();
            stack.createSavepoint();

            // then
            assertThatCode(stack::commit).doesNotThrowAnyException();
            assertThatCode(stack::commit).doesNotThrowAnyException();
            assertThatThrownBy(stack::commit).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Test for rolling back savepoints")
    class RollbackTests {
        @Test
        void testRolledBackSavepoint() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);

            // when
            stack.rollback();

            // then
            assertThat(stack.depth()).isEqualTo(1);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(BASE_DATA));
            assertThat(writableStatesStack).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        }

        @Test
        void testModificationsAfterRollback() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.rollback();

            // when
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);

            // then
            assertThat(stack.depth()).isEqualTo(1);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testNewSavepointAfterRollback() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.rollback();

            // when
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);

            // then
            assertThat(stack.depth()).isEqualTo(2);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(C_KEY, CRANBERRY);
            newData.put(D_KEY, DRAGONFRUIT);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testMultipleRollbacks() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var readableStatesStack = stack.getReadableStates(FOOD_SERVICE);
            final var writableStatesStack = stack.getWritableStates(FOOD_SERVICE);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(C_KEY, CRANBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(D_KEY, DRAGONFRUIT);
            stack.createSavepoint();
            writableStatesStack.get(FRUIT_STATE_ID).put(E_KEY, ELDERBERRY);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(F_KEY, FEIJOA);

            // when
            stack.rollback();
            stack.rollback();

            // then
            assertThat(stack.depth()).isEqualTo(2);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStatesStack);
            assertThat(readableStatesStack).has(content(newData));
            assertThat(writableStatesStack).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testRollbackInitialStackFails() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);

            // then
            assertThatThrownBy(stack::rollback).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void testTooManyRollbacksFail() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            stack.createSavepoint();
            stack.createSavepoint();

            // then
            assertThatCode(stack::rollback).doesNotThrowAnyException();
            assertThatCode(stack::rollback).doesNotThrowAnyException();
            assertThatThrownBy(stack::rollback).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Tests for committing the full stack")
    class FullStackCommitTests {
        @Test
        void testCommitFullStack() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var writableState = stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID);
            writableState.put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));

            // when
            stack.commitFullStack();

            // then
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testCommitFullStackAfterSingleCommit() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            stack.createSavepoint();
            final var writableState = stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID);
            writableState.put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));

            // when
            stack.commit();
            stack.commitFullStack();

            // then
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);
            newData.put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }

        @Test
        void testCommitFullStackAfterRollback() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            stack.createSavepoint();
            final var writableState = stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID);
            writableState.put(A_KEY, ACAI);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(B_KEY, BLUEBERRY);
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));

            // when
            stack.rollback();
            stack.commitFullStack();

            // then
            assertThat(baseState.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        }

        @Test
        void testStackAfterCommitFullStack() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);

            // when
            stack.commitFullStack();

            // then
            assertThatThrownBy(stack::commit).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(stack::rollback).isInstanceOf(IllegalStateException.class);
            assertThat(stack.depth()).isOne();
            assertThatCode(stack::commitFullStack).doesNotThrowAnyException();
            assertThatCode(stack::createSavepoint).doesNotThrowAnyException();
        }

        @Test
        void testReuseAfterCommitFullStack() {
            // given
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    streamMode,
                    TraceDataSizeLimiter.NO_LIMIT);
            final var writableState = stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID);
            writableState.put(A_KEY, ACAI);
            final var newData = new HashMap<>(BASE_DATA);
            newData.put(A_KEY, ACAI);

            // when
            stack.commitFullStack();

            // then
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.rootStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(newData));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(newData));
        }
    }

    @Nested
    @DisplayName("Tests for resetting a root stack for the next user transaction")
    class ResetForNextUserTxnTests {
        @Test
        void resetKeepsStateAdaptersAndIsolatesUncommittedMutations() {
            final var stack = newRootStack(3, 50, streamMode);
            final var writableStates = stack.getWritableStates(FOOD_SERVICE);
            final var readableStates = stack.getReadableStates(FOOD_SERVICE);
            final var fruit = writableStates.get(FRUIT_STATE_ID);
            final var wrap = stack.peek().state();
            final var firstBuilder = stack.getBaseBuilder(StreamBuilder.class);
            firstBuilder
                    .transactionID(TransactionID.newBuilder()
                            .accountID(PAYER_ID)
                            .transactionValidStart(VALID_START)
                            .build())
                    .status(SUCCESS);
            fruit.put(A_KEY, ACAI);

            assertThat(stack.resetForNextUserTxn(
                            baseState,
                            3,
                            50,
                            roundStateChangeListener,
                            immediateStateChangeListener,
                            streamMode,
                            TraceDataSizeLimiter.NO_LIMIT))
                    .isTrue();

            assertThat(stack.depth()).isOne();
            assertThat(stack.peek().state()).isSameAs(wrap);
            assertThat(stack.getWritableStates(FOOD_SERVICE)).isSameAs(writableStates);
            assertThat(stack.getReadableStates(FOOD_SERVICE)).isSameAs(readableStates);
            assertThat(writableStates.get(FRUIT_STATE_ID)).isSameAs(fruit);
            assertThat(stack.getBaseBuilder(StreamBuilder.class)).isSameAs(firstBuilder);
            assertThat(firstBuilder.status()).isEqualTo(OK);
            assertThat(firstBuilder.transactionID()).isNull();
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
            assertThat(baseState.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        }

        @Test
        void resetAfterCommitKeepsCommittedStateAndDropsPriorBuilders() {
            final var firstId = TransactionID.newBuilder()
                    .accountID(PAYER_ID)
                    .transactionValidStart(VALID_START)
                    .build();
            final var stack = newRootStack(3, 50, StreamMode.RECORDS);
            stack.getBaseBuilder(StreamBuilder.class)
                    .transactionID(firstId)
                    .signedTx(SignedTransaction.DEFAULT)
                    .status(SUCCESS)
                    .exchangeRate(ExchangeRateSet.DEFAULT);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.commitFullStack();
            stack.buildHandleOutput(
                    Instant.ofEpochSecond(VALID_START.seconds(), VALID_START.nanos()),
                    ExchangeRateSet.DEFAULT,
                    BLOCK_NUMBER);

            assertThat(stack.resetForNextUserTxn(
                            baseState,
                            3,
                            50,
                            roundStateChangeListener,
                            immediateStateChangeListener,
                            StreamMode.RECORDS,
                            TraceDataSizeLimiter.NO_LIMIT))
                    .isTrue();

            final var committed = new HashMap<>(BASE_DATA);
            committed.put(A_KEY, ACAI);
            assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(committed));
            assertThat(stack.rootHasPrecedingCapacity()).isTrue();

            final var secondId = TransactionID.newBuilder()
                    .accountID(PAYER_ID)
                    .transactionValidStart(new Timestamp(2_345_678L, 0))
                    .build();
            stack.getBaseBuilder(StreamBuilder.class)
                    .transactionID(secondId)
                    .signedTx(SignedTransaction.DEFAULT)
                    .status(SUCCESS)
                    .exchangeRate(ExchangeRateSet.DEFAULT);
            stack.commitFullStack();
            final var handleOutput = stack.buildHandleOutput(
                    Instant.ofEpochSecond(2_345_678L, 0), ExchangeRateSet.DEFAULT, BLOCK_NUMBER);
            final var records = new java.util.ArrayList<com.hedera.hapi.node.transaction.TransactionRecord>();
            handleOutput.recordSourceOrThrow().forEachTxnRecord(records::add);

            assertThat(records)
                    .singleElement()
                    .extracting(com.hedera.hapi.node.transaction.TransactionRecord::transactionID)
                    .isEqualTo(secondId);
        }

        @Test
        void getOriginalValueSeesSoftCommittedModsNotRootReadable() {
            final var writableKv =
                    new MapWritableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, new HashMap<>(BASE_DATA));
            final var writableStates = new MapWritableStates(Map.of(FRUIT_STATE_ID, writableKv)) {
                @Override
                public boolean requiresImmediateCommit() {
                    return false;
                }
            };
            final var readableStates = MapReadableStates.builder()
                    .state(new MapReadableKVState<>(FRUIT_STATE_ID, FRUIT_STATE_LABEL, new HashMap<>(BASE_DATA)))
                    .build();
            when(baseState.getWritableStates(FOOD_SERVICE)).thenReturn(writableStates);
            when(baseState.getReadableStates(FOOD_SERVICE)).thenReturn(readableStates);

            final var stack = newRootStack(3, 50, streamMode);
            stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID).put(A_KEY, ACAI);
            stack.commitFullStack();

            assertThat(stack.resetForNextUserTxn(
                            baseState,
                            3,
                            50,
                            roundStateChangeListener,
                            immediateStateChangeListener,
                            streamMode,
                            TraceDataSizeLimiter.NO_LIMIT))
                    .isTrue();

            final var fruit = stack.getWritableStates(FOOD_SERVICE).get(FRUIT_STATE_ID);
            assertThat(fruit.getOriginalValue(A_KEY)).isEqualTo(ACAI);
            assertThat(stack.rootStates(FOOD_SERVICE).get(FRUIT_STATE_ID).get(A_KEY))
                    .isEqualTo(APPLE);

            fruit.put(A_KEY, APPLE);
            assertThat(fruit.getOriginalValue(A_KEY)).isEqualTo(ACAI);
            assertThat(fruit.get(A_KEY)).isEqualTo(APPLE);
        }

        @Test
        void resetRestoresPresetIdCapacity() {
            final var vanillaBaseId = TransactionID.newBuilder()
                    .accountID(PAYER_ID)
                    .transactionValidStart(VALID_START)
                    .build();
            final var stack = newRootStack(3, 50, StreamMode.BOTH);
            stack.getBaseBuilder(StreamBuilder.class).transactionID(vanillaBaseId);
            stack.nextPresetTxnId(true);

            assertThat(stack.resetForNextUserTxn(
                            baseState,
                            3,
                            50,
                            roundStateChangeListener,
                            immediateStateChangeListener,
                            StreamMode.BOTH,
                            TraceDataSizeLimiter.NO_LIMIT))
                    .isTrue();

            stack.getBaseBuilder(StreamBuilder.class).transactionID(vanillaBaseId);
            assertThat(stack.nextPresetTxnId(false))
                    .isEqualTo(vanillaBaseId.copyBuilder().nonce(53).build());
        }

        @Test
        void resetRejectedForChildStackOrChangedBuilderLimits() {
            final var root = newRootStack(3, 50, streamMode);
            final var child = SavepointStackImpl.newChildStack(
                    root, REVERSIBLE, HandleContext.TransactionCategory.CHILD, NOOP_SIGNED_TX_CUSTOMIZER, streamMode);
            final var originalBuilder = root.getBaseBuilder(StreamBuilder.class);

            assertThat(child.resetForNextUserTxn(
                            baseState,
                            3,
                            50,
                            roundStateChangeListener,
                            immediateStateChangeListener,
                            streamMode,
                            TraceDataSizeLimiter.NO_LIMIT))
                    .isFalse();
            assertThat(root.resetForNextUserTxn(
                            baseState,
                            4,
                            50,
                            roundStateChangeListener,
                            immediateStateChangeListener,
                            streamMode,
                            TraceDataSizeLimiter.NO_LIMIT))
                    .isFalse();
            assertThat(root.getBaseBuilder(StreamBuilder.class)).isSameAs(originalBuilder);
        }

        private SavepointStackImpl newRootStack(final int maxBefore, final int maxAfter, final StreamMode mode) {
            return SavepointStackImpl.newRootStack(
                    baseState,
                    maxBefore,
                    maxAfter,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    mode,
                    TraceDataSizeLimiter.NO_LIMIT);
        }
    }

    private static Condition<ReadableStates> content(Map<ProtoBytes, ProtoBytes> expected) {
        return new Condition<>(contentCheck(expected), "state " + expected);
    }

    private static Predicate<ReadableStates> contentCheck(Map<ProtoBytes, ProtoBytes> expected) {
        return readableStates -> {
            final var actual = readableStates.get(FRUIT_STATE_ID);
            if (expected.size() != actual.size()) {
                return false;
            }
            for (final var entry : expected.entrySet()) {
                if (!Objects.equals(entry.getValue(), actual.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        };
    }
}
