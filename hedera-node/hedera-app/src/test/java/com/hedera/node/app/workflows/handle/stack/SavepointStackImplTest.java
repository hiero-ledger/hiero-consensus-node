// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static com.hedera.hapi.node.base.HederaFunctionality.ATOMIC_BATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECURSIVE_SCHEDULING_LIMIT_REACHED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.PRECEDING;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REMOVABLE;
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
import com.hedera.hapi.node.transaction.TransactionRecord;
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
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                .isEqualTo(vanillaBaseId.copyBuilder().nonce(54).build());
        assertThat(secondPresetId)
                .isEqualTo(vanillaBaseId.copyBuilder().nonce(2 * 54).build());
    }

    @Test
    @DisplayName("a preset id cannot collide with a sequentially assigned child nonce at the stride boundary")
    void presetIdsDoNotCollideWithSequentialChildNonces() {
        final int maxPreceding = 3;
        final int maxFollowing = 50;
        final var baseId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .build();
        final var stack = SavepointStackImpl.newRootStack(
                baseState,
                maxPreceding,
                maxFollowing,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.RECORDS,
                TraceDataSizeLimiter.NO_LIMIT);
        initialized(stack.getBaseBuilder(StreamBuilder.class)).transactionID(baseId);

        // Saturate the preceding budget, whose builders are numbered ahead of the following ones
        for (int i = 0; i < maxPreceding; i++) {
            initialized(stack.createIrreversiblePrecedingBuilder());
        }
        // The first child takes the preset id an HSS scheduleCall dispatch would get; it keeps that id, but
        // still consumes a sequential offset, so a later child can be numbered onto the same nonce
        final var presetId = stack.nextPresetTxnId(false);
        addChildTo(stack).transactionID(presetId);
        for (int i = 1; i < maxFollowing; i++) {
            addChildTo(stack);
        }
        stack.commitFullStack();

        final List<TransactionRecord> records = new ArrayList<>();
        stack.buildHandleOutput(
                        Instant.ofEpochSecond(VALID_START.seconds(), VALID_START.nanos()), ExchangeRateSet.DEFAULT)
                .recordSourceOrThrow()
                .forEachTxnRecord(records::add);

        assertThat(records).hasSize(1 + maxPreceding + maxFollowing);
        assertThat(records.stream().map(TransactionRecord::transactionIDOrThrow).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a scheduled execution gets no preset id after scheduling a contract call")
    void scheduledExecutionGetsNoPresetIdAfterSchedulingAContractCall() {
        final int maxPreceding = 3;
        final int maxFollowing = 50;
        // A schedule created by an earlier transaction executes in its own unit, keeping the nonce it was
        // handed as a preset id and carrying scheduled=true
        final var scheduledBaseId = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .scheduled(true)
                .nonce(maxPreceding + maxFollowing + 1)
                .build();
        final var stack = rootStackWith(scheduledBaseId, maxPreceding, maxFollowing);

        // Scheduling a contract call is the last preset id a unit may take, c.f. RECURSIVE_FUNCTIONS in
        // ChildDispatchFactory; without that no further preset range is reserved, and the nonces of a schedule
        // this one creates cannot reach one
        final var presetId = stack.nextPresetTxnId(true);

        assertThat(presetId)
                .isEqualTo(scheduledBaseId
                        .copyBuilder()
                        .nonce(scheduledBaseId.nonce() + maxPreceding + maxFollowing + 1)
                        .build());
        assertThatThrownBy(() -> stack.nextPresetTxnId(false))
                .isInstanceOf(HandleException.class)
                .hasMessage(NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION.protoName());
    }

    /**
     * Returns a root stack whose base builder carries the given transaction ID.
     */
    private SavepointStackImpl rootStackWith(
            final TransactionID baseId, final int maxPreceding, final int maxFollowing) {
        final var stack = SavepointStackImpl.newRootStack(
                baseState,
                maxPreceding,
                maxFollowing,
                roundStateChangeListener,
                immediateStateChangeListener,
                StreamMode.RECORDS,
                TraceDataSizeLimiter.NO_LIMIT);
        initialized(stack.getBaseBuilder(StreamBuilder.class)).transactionID(baseId);
        return stack;
    }

    /**
     * Adds a committed {@code CHILD} builder to the given root stack's following builders.
     */
    private StreamBuilder addChildTo(final SavepointStackImpl root) {
        final var childStack = SavepointStackImpl.newChildStack(
                root, REVERSIBLE, CHILD, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.RECORDS);
        final var builder = initialized(childStack.getBaseBuilder(StreamBuilder.class));
        childStack.commitFullStack();
        return builder;
    }

    /**
     * Sets the minimum fields a builder needs to be externalized as a record.
     */
    private StreamBuilder initialized(final StreamBuilder builder) {
        return builder.signedTx(SignedTransaction.DEFAULT).status(SUCCESS).exchangeRate(ExchangeRateSet.DEFAULT);
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
        assertThat(presetId).isEqualTo(vanillaBaseId.copyBuilder().nonce(54).build());
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
        assertThat(stack.getReadableStates(FOOD_SERVICE)).has(content(BASE_DATA));
        assertThat(stack.getWritableStates(FOOD_SERVICE)).has(content(BASE_DATA));
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
    @DisplayName("Tests for attributing ids to synthetic records dispatched inside an atomic batch")
    class AtomicBatchIdentityTests {
        private static final TransactionID BATCH_ID = TransactionID.newBuilder()
                .accountID(PAYER_ID)
                .transactionValidStart(VALID_START)
                .build();
        private static final TransactionID INNER_A_ID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1001L).build())
                .transactionValidStart(new Timestamp(1_234_500L, 1))
                .build();
        private static final TransactionID INNER_B_ID = TransactionID.newBuilder()
                .accountID(AccountID.newBuilder().accountNum(1002L).build())
                .transactionValidStart(new Timestamp(1_234_501L, 2))
                .build();

        @Test
        @DisplayName("a preceding dispatch flushed out of an inner transaction's savepoint keeps that inner's identity")
        void lateFlushedPrecedingKeepsItsOwnBatchInnerIdentity() {
            final var stack = batchRootStack();

            // Inner A is a contract call that lazy-creates an account; as the EVM does, it opens a savepoint
            // before dispatching, so the synthetic creation is only flushed when the EVM transaction commits,
            // landing *after* inner A in the root sink
            final var innerA = batchInnerStackIn(stack, INNER_A_ID);
            innerA.createSavepoint();
            final var creation = precedingDispatchIn(innerA);
            innerA.commit();
            innerA.commitFullStack();

            // Inner B is an unrelated transfer that happens to follow inner A
            batchInnerStackIn(stack, INNER_B_ID).commitFullStack();
            stack.commitFullStack();

            assertThat(idsFrom(stack))
                    .containsExactly(
                            BATCH_ID,
                            INNER_A_ID,
                            INNER_A_ID.copyBuilder().nonce(1).build(),
                            INNER_B_ID);
            assertThat(creation.transactionID())
                    .isEqualTo(INNER_A_ID.copyBuilder().nonce(1).build());
        }

        @Test
        @DisplayName("a preceding dispatch flushed ahead of its inner transaction keeps that inner's identity")
        void earlyFlushedPrecedingKeepsItsOwnBatchInnerIdentity() {
            final var stack = batchRootStack();

            // Inner A is a transfer that auto-creates an aliased receiver; with no intervening savepoint the
            // synthetic creation is flushed *ahead* of inner A in the root sink
            final var innerA = batchInnerStackIn(stack, INNER_A_ID);
            precedingDispatchIn(innerA);
            innerA.commitFullStack();

            batchInnerStackIn(stack, INNER_B_ID).commitFullStack();
            stack.commitFullStack();

            assertThat(idsFrom(stack))
                    .containsExactly(BATCH_ID, INNER_A_ID.copyBuilder().nonce(1).build(), INNER_A_ID, INNER_B_ID);
        }

        @Test
        @DisplayName("a preceding dispatch in the last inner transaction keeps that inner's identity")
        void precedingInLastBatchInnerKeepsThatInnerIdentity() {
            final var stack = batchRootStack();

            batchInnerStackIn(stack, INNER_A_ID).commitFullStack();
            final var innerB = batchInnerStackIn(stack, INNER_B_ID);
            innerB.createSavepoint();
            precedingDispatchIn(innerB);
            innerB.commit();
            innerB.commitFullStack();
            stack.commitFullStack();

            assertThat(idsFrom(stack))
                    .containsExactly(
                            BATCH_ID,
                            INNER_A_ID,
                            INNER_B_ID,
                            INNER_B_ID.copyBuilder().nonce(1).build());
        }

        @Test
        @DisplayName("a preceding dispatch is not confused by a child record of an earlier inner transaction")
        void precedingIsUnaffectedByChildOfEarlierBatchInner() {
            final var stack = batchRootStack();

            final var innerA = batchInnerStackIn(stack, INNER_A_ID);
            initialized(innerA.createRemovableChildBuilder());
            innerA.commitFullStack();

            final var innerB = batchInnerStackIn(stack, INNER_B_ID);
            innerB.createSavepoint();
            precedingDispatchIn(innerB);
            innerB.commit();
            innerB.commitFullStack();
            stack.commitFullStack();

            assertThat(idsFrom(stack))
                    .containsExactly(
                            BATCH_ID,
                            INNER_A_ID,
                            INNER_A_ID.copyBuilder().nonce(1).build(),
                            INNER_B_ID,
                            INNER_B_ID.copyBuilder().nonce(2).build());
        }

        @Test
        @DisplayName("a child record dispatched inside an inner transaction keeps that inner's identity")
        void childKeepsItsOwnBatchInnerIdentity() {
            final var stack = batchRootStack();

            batchInnerStackIn(stack, INNER_A_ID).commitFullStack();
            final var innerB = batchInnerStackIn(stack, INNER_B_ID);
            initialized(innerB.createRemovableChildBuilder());
            innerB.commitFullStack();
            stack.commitFullStack();

            assertThat(idsFrom(stack))
                    .containsExactly(
                            BATCH_ID,
                            INNER_A_ID,
                            INNER_B_ID,
                            INNER_B_ID.copyBuilder().nonce(1).build());
        }

        @Test
        @DisplayName("a preceding dispatch of the batch itself keeps the batch's identity")
        void precedingOutsideAnyBatchInnerKeepsTheBatchIdentity() {
            final var stack = batchRootStack();

            // For example, completing a hollow account that is paying for the batch itself
            precedingDispatchIn(stack);
            batchInnerStackIn(stack, INNER_A_ID).commitFullStack();
            stack.commitFullStack();

            assertThat(idsFrom(stack))
                    .containsExactly(BATCH_ID.copyBuilder().nonce(1).build(), BATCH_ID, INNER_A_ID);
        }

        @Test
        @DisplayName("a late-flushed preceding record reports its own inner transaction as its parent")
        void lateFlushedPrecedingReportsItsOwnBatchInnerAsParent() {
            final var stack = batchRootStack();

            batchInnerStackIn(stack, INNER_A_ID).commitFullStack();
            final var innerB = batchInnerStackIn(stack, INNER_B_ID);
            innerB.createSavepoint();
            precedingDispatchIn(innerB);
            innerB.commit();
            innerB.commitFullStack();
            stack.commitFullStack();

            // batch@0, innerA@1, innerB@2, preceding@3 -- the preceding record belongs to innerB, so its parent
            // consensus time is innerB's, not the batch's
            assertThat(parentConsensusNanosFrom(stack)).containsExactly(null, 0, 0, 2);
        }

        @Test
        @DisplayName("an early-flushed preceding record reports its own inner transaction as its parent")
        void earlyFlushedPrecedingReportsItsOwnBatchInnerAsParent() {
            final var stack = batchRootStack();

            final var innerA = batchInnerStackIn(stack, INNER_A_ID);
            precedingDispatchIn(innerA);
            innerA.commitFullStack();
            batchInnerStackIn(stack, INNER_B_ID).commitFullStack();
            stack.commitFullStack();

            // batch@0, preceding@1, innerA@2, innerB@3 -- the preceding record is flushed ahead of innerA but still
            // belongs to it, so it reports innerA's consensus time even though that is later than its own
            assertThat(parentConsensusNanosFrom(stack)).containsExactly(null, 2, 0, 0);
        }

        @Test
        @DisplayName("a child record continues to report its own inner transaction as its parent")
        void childReportsItsOwnBatchInnerAsParent() {
            final var stack = batchRootStack();

            batchInnerStackIn(stack, INNER_A_ID).commitFullStack();
            final var innerB = batchInnerStackIn(stack, INNER_B_ID);
            initialized(innerB.createRemovableChildBuilder());
            innerB.commitFullStack();
            stack.commitFullStack();

            assertThat(parentConsensusNanosFrom(stack)).containsExactly(null, 0, 0, 2);
        }

        @Test
        @DisplayName("a preceding dispatch of the batch itself reports the batch as its parent")
        void precedingOutsideAnyBatchInnerReportsTheBatchAsParent() {
            final var stack = batchRootStack();

            precedingDispatchIn(stack);
            batchInnerStackIn(stack, INNER_A_ID).commitFullStack();
            stack.commitFullStack();

            // preceding@-1, batch@0, innerA@1 -- a record flushed ahead of the user transaction is not given a
            // parent consensus time at all, which this fix leaves untouched
            assertThat(parentConsensusNanosFrom(stack)).containsExactly(null, null, 0);
        }

        private SavepointStackImpl batchRootStack() {
            final var stack = SavepointStackImpl.newRootStack(
                    baseState,
                    3,
                    50,
                    roundStateChangeListener,
                    immediateStateChangeListener,
                    StreamMode.RECORDS,
                    TraceDataSizeLimiter.NO_LIMIT);
            initialized(stack.getBaseBuilder(StreamBuilder.class))
                    .functionality(ATOMIC_BATCH)
                    .transactionID(BATCH_ID);
            return stack;
        }

        private SavepointStackImpl batchInnerStackIn(final SavepointStackImpl root, final TransactionID innerTxnId) {
            final var innerStack = SavepointStackImpl.newChildStack(
                    root, REVERSIBLE, BATCH_INNER, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.RECORDS);
            initialized(innerStack.getBaseBuilder(StreamBuilder.class)).transactionID(innerTxnId);
            return innerStack;
        }

        /**
         * Dispatches a synthetic setup transaction in the given stack, as a lazy account creation does; note it is
         * given no transaction id of its own, so one must be assigned when the user transaction is built.
         */
        private StreamBuilder precedingDispatchIn(final SavepointStackImpl parentStack) {
            final var precedingStack = SavepointStackImpl.newChildStack(
                    parentStack, REMOVABLE, PRECEDING, NOOP_SIGNED_TX_CUSTOMIZER, StreamMode.RECORDS);
            final var builder = initialized(precedingStack.getBaseBuilder(StreamBuilder.class));
            precedingStack.commitFullStack();
            return builder;
        }

        /**
         * Returns each record's {@code parentConsensusTimestamp} as nanos relative to the user transaction, or null
         * where the field is unset.
         */
        private List<Integer> parentConsensusNanosFrom(final SavepointStackImpl stack) {
            final List<TransactionRecord> records = new ArrayList<>();
            stack.buildHandleOutput(
                            Instant.ofEpochSecond(VALID_START.seconds(), VALID_START.nanos()), ExchangeRateSet.DEFAULT)
                    .recordSourceOrThrow()
                    .forEachTxnRecord(records::add);
            return records.stream()
                    .map(record -> record.parentConsensusTimestamp() == null
                            ? null
                            : record.parentConsensusTimestampOrThrow().nanos() - VALID_START.nanos())
                    .toList();
        }

        private List<TransactionID> idsFrom(final SavepointStackImpl stack) {
            final List<TransactionRecord> records = new ArrayList<>();
            stack.buildHandleOutput(
                            Instant.ofEpochSecond(VALID_START.seconds(), VALID_START.nanos()), ExchangeRateSet.DEFAULT)
                    .recordSourceOrThrow()
                    .forEachTxnRecord(records::add);
            return records.stream().map(TransactionRecord::transactionIDOrThrow).toList();
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
