// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static com.hedera.hapi.node.base.HederaFunctionality.ATOMIC_BATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.HOOK_DISPATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECURSIVE_SCHEDULING_LIMIT_REACHED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.PRECEDING;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.IRREVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REMOVABLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.SignedTxCustomizer.NOOP_SIGNED_TX_CUSTOMIZER;
import static com.hedera.node.app.workflows.handle.stack.savepoints.AbstractSavepoint.SUCCESSES;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.ImmediateStateChangeListener;
import com.hedera.node.app.blocks.impl.PairedStreamBuilder;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.state.ReadonlyStatesWrapper;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.app.state.WrappedState;
import com.hedera.node.app.state.recordcache.BlockRecordSource;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.workflows.handle.HandleOutput;
import com.hedera.node.app.workflows.handle.record.RecordStreamBuilder;
import com.hedera.node.app.workflows.handle.stack.savepoints.BuilderSinkImpl;
import com.hedera.node.app.workflows.handle.stack.savepoints.FirstChildSavepoint;
import com.hedera.node.app.workflows.handle.stack.savepoints.FirstRootSavepoint;
import com.hedera.node.app.workflows.handle.stack.savepoints.FollowingSavepoint;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.hiero.base.crypto.Hash;

/**
 * A stack of savepoints scoped to a dispatch. Each savepoint captures the state of the {@link State} at the time
 * the savepoint was created and all the changes made to the state from the time savepoint was created, along with all
 * the stream builders created in the savepoint.
 */
public class SavepointStackImpl implements HandleContext.SavepointStack, State {
    private final State state;
    private final Deque<Savepoint> stack = new ArrayDeque<>();
    private final Map<String, WritableStatesStack> writableStatesMap = new HashMap<>();
    /**
     * The stream builder for the transaction whose dispatch created this stack.
     */
    private final StreamBuilder baseBuilder;
    // For the root stack of a user dispatch, the final sink of all created stream builders; otherwise null,
    // because child stacks flush their builders into the savepoint at the top of their parent stack
    @Nullable
    private final BuilderSink builderSink;

    /**
     * For the root stack of a user dispatch, the transaction id of the batch inner transaction that dispatched each
     * builder still needing an id when {@link #buildHandleOutput(Instant, ExchangeRateSet, Long)} runs; null until
     * some builder is dispatched within a batch inner transaction, and always null for a child stack, which records
     * its builders' owners in the root stack.
     * <p>
     * These owners have to be recorded when the dispatch happens, because they cannot be recovered from the position
     * of a builder in the root sink. A {@link TransactionCategory#PRECEDING} builder is flushed <i>before</i> its
     * inner transaction in some cases (for example, an account auto-created to receive value in a
     * {@code CryptoTransfer}); but <i>after</i> it in others (for example, an account lazy-created by the EVM, whose
     * dispatch escapes the enclosing savepoint only when the EVM transaction commits).
     */
    @Nullable
    private Map<StreamBuilder, TransactionID> batchInnerIdsByBuilder;

    @Nullable
    private final ImmediateStateChangeListener immediateStateChangeListener;

    @Nullable
    private final BoundaryStateChangeListener boundaryStateChangeListener;

    private final StreamMode streamMode;

    private int numPresetIds;
    private int noncesPerPresetId;
    private boolean presetIdsAllowed;

    /**
     * Constructs the root {@link SavepointStackImpl} for the given state at the start of handling a user transaction.
     *
     * @param state the state
     * @param maxBuildersBeforeUser the maximum number of preceding builders with available consensus times
     * @param maxBuildersAfterUser the maximum number of following builders with available consensus times
     * @param boundaryStateChangeListener the listener for the round state changes
     * @param immediateStateChangeListener the listener for the key/value state changes
     * @param streamMode the stream mode
     * @param maxSerializedTraceDataBytes the maximum estimated serialized contract trace data size in bytes
     * @return the root {@link SavepointStackImpl}
     */
    public static SavepointStackImpl newRootStack(
            @NonNull final State state,
            final int maxBuildersBeforeUser,
            final int maxBuildersAfterUser,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final ImmediateStateChangeListener immediateStateChangeListener,
            @NonNull final StreamMode streamMode,
            final int maxSerializedTraceDataBytes) {
        return new SavepointStackImpl(
                state,
                maxBuildersBeforeUser,
                maxBuildersAfterUser,
                boundaryStateChangeListener,
                immediateStateChangeListener,
                streamMode,
                maxSerializedTraceDataBytes);
    }

    /**
     * Constructs a new child {@link SavepointStackImpl} for the given state, where the child dispatch has the given
     * reversing behavior, transaction category, and record customizer.
     *
     * @param root              the state on which the child dispatch is based
     * @param reversingBehavior the reversing behavior for the initial dispatch
     * @param category          the transaction category
     * @param customizer        the record customizer
     * @param streamMode        the stream mode
     * @return the child {@link SavepointStackImpl}
     */
    public static SavepointStackImpl newChildStack(
            @NonNull final SavepointStackImpl root,
            @NonNull final StreamBuilder.ReversingBehavior reversingBehavior,
            @NonNull final TransactionCategory category,
            @NonNull final StreamBuilder.SignedTxCustomizer customizer,
            @NonNull final StreamMode streamMode) {
        return new SavepointStackImpl(root, reversingBehavior, category, customizer, streamMode);
    }

    /**
     * Constructs a new root {@link SavepointStackImpl} with the given root state.
     *
     * @param state                         the state
     * @param maxBuildersBeforeUser         the maximum number of preceding builders to create
     * @param maxBuildersAfterUser          the maximum number of following builders to create
     * @param boundaryStateChangeListener   the listener for the round state changes
     * @param immediateStateChangeListener  the listener for the key-value state changes
     * @param streamMode                    the stream mode
     */
    private SavepointStackImpl(
            @NonNull final State state,
            final int maxBuildersBeforeUser,
            final int maxBuildersAfterUser,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final ImmediateStateChangeListener immediateStateChangeListener,
            @NonNull final StreamMode streamMode,
            final int maxSerializedTraceDataBytes) {
        this.state = requireNonNull(state);
        this.immediateStateChangeListener = requireNonNull(immediateStateChangeListener);
        this.boundaryStateChangeListener = requireNonNull(boundaryStateChangeListener);
        this.streamMode = requireNonNull(streamMode);
        builderSink = new BuilderSinkImpl(maxBuildersBeforeUser, maxBuildersAfterUser + 1);
        presetIdsAllowed = true;
        // The +1 puts preset nonces strictly past the largest offset buildHandleOutput() can assign
        noncesPerPresetId = maxBuildersBeforeUser + maxBuildersAfterUser + 1;
        setupFirstSavepoint(USER);
        baseBuilder = createRootBaseBuilder(maxSerializedTraceDataBytes);
    }

    /**
     * Constructs a new child {@link SavepointStackImpl} with the given parent stack and the provided
     * characteristics of the dispatch.
     *
     * @param parent            the parent stack
     * @param reversingBehavior the reversing behavior of the dispatch
     * @param category          the category of the dispatch
     * @param customizer        the record customizer for the dispatch
     * @param streamMode        the stream mode
     */
    private SavepointStackImpl(
            @NonNull final SavepointStackImpl parent,
            @NonNull final StreamBuilder.ReversingBehavior reversingBehavior,
            @NonNull final TransactionCategory category,
            @NonNull final StreamBuilder.SignedTxCustomizer customizer,
            @NonNull final StreamMode streamMode) {
        requireNonNull(reversingBehavior);
        requireNonNull(customizer);
        requireNonNull(category);
        this.streamMode = requireNonNull(streamMode);
        this.state = requireNonNull(parent);
        this.builderSink = null;
        this.immediateStateChangeListener = null;
        this.boundaryStateChangeListener = null;
        setupFirstSavepoint(category);
        baseBuilder = peek().createBuilder(reversingBehavior, category, customizer, streamMode, true);
        trackAnyEnclosingBatchInner(baseBuilder);
        presetIdsAllowed = false;
    }

    @Override
    public void createSavepoint() {
        stack.push(new FollowingSavepoint(new WrappedState(peek().state()), peek()));
    }

    @Override
    public void commit() {
        if (stack.size() <= 1) {
            throw new IllegalStateException("The savepoint stack is empty");
        }
        stack.pop().commit();
    }

    @Override
    public void rollback() {
        if (stack.size() <= 1) {
            throw new IllegalStateException("The savepoint stack is empty");
        }
        stack.pop().rollback();
    }

    @Override
    public int depth() {
        return stack.size();
    }

    /**
     * Commits all state changes captured in this stack, without capturing the details
     * for the block stream.
     *
     * @throws NullPointerException if called on the root stack
     */
    public void commitFullStack() {
        commitTransaction(baseBuilder);
    }

    /**
     * Commits all state changes captured in this stack; and captures the details for
     * the block stream, correlated to the given builder.
     *
     * @param builder the builder to correlate the state changes to
     */
    public void commitTransaction(@NonNull final StreamBuilder builder) {
        requireNonNull(builder);
        if (streamMode != RECORDS && immediateStateChangeListener != null) {
            immediateStateChangeListener.reset(builder.logicallyIdenticalValueTest());
        }
        while (!stack.isEmpty()) {
            final var savepoint = stack.pop();
            // If this is a root stack, track the collected node fees for the block
            if (boundaryStateChangeListener != null && stack.isEmpty()) {
                boundaryStateChangeListener.trackCollectedNodeFees(savepoint.getNodeFeesCollected());
            }
            savepoint.commit();
        }
        if (streamMode != RECORDS && immediateStateChangeListener != null) {
            builder.stateChanges(immediateStateChangeListener.getStateChanges());
        }
        setupFirstSavepoint(baseBuilder.category());
    }

    /**
     * Rolls back all state changes captured in this stack.
     */
    public void rollbackFullStack() {
        while (!stack.isEmpty()) {
            stack.pop().rollback();
        }
        // Rolling back is idempotent for stream builders; so make certain here that we do it for our base builder
        // (if we were used to persist stake-period changes, it will have already been "flushed" into our sink)
        baseBuilder.nullOutSideEffectFields();
        if (SUCCESSES.contains(baseBuilder.status())) {
            baseBuilder.status(REVERTED_SUCCESS);
        }
        setupFirstSavepoint(baseBuilder.category());
    }

    /**
     * Returns true when this stack's base builder should be finalized with staking rewards. There are
     * two qualifying cases:
     * <ol>
     *     <li>The stack is for top-level transaction (either a user transaction or a triggered execution
     *     like a expiring scheduled transaction with {@code wait_for_expiry=true}); or,</li>
     *     <li>The stack is for executing a scheduled transaction with {@code wait_for_expiry=false}, and
     *     whose triggering parent was a user transaction.</li>
     * </ol>
     * The second category is solely for backward compatibility with mono-service, and should be considered
     * for deprecation and removal.
     */
    public boolean permitsStakingRewards() {
        return builderSink != null
                ||
                // For backward compatibility with mono-service, we permit paying staking rewards to
                // scheduled transactions that are exactly children of user transactions
                (baseBuilder.category() == SCHEDULED
                        && state instanceof SavepointStackImpl parent
                        && parent.txnCategory() == USER);
    }

    /**
     * Returns the root {@link ReadableStates} for the given service name.
     *
     * @param serviceName the name of the service
     * @return the root {@link ReadableStates} for the given service name
     */
    @NonNull
    public ReadableStates rootStates(@NonNull final String serviceName) {
        return state.getReadableStates(serviceName);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The {@link ReadableStates} instances returned from this method are based on the {@link WritableStates} instances
     * for the same service name. This means that any modifications to the {@link WritableStates} will be reflected
     * in the {@link ReadableStates} instances returned from this method.
     * <p>
     * Unlike other {@link State} implementations, the returned {@link ReadableStates} of this implementation
     * must only be used in the handle workflow.
     */
    @Override
    @NonNull
    public ReadableStates getReadableStates(@NonNull String serviceName) {
        return new ReadonlyStatesWrapper(getWritableStates(serviceName));
    }

    /**
     * {@inheritDoc}
     * <p>
     * This method guarantees that the same {@link WritableStates} instance is returned for the same {@code serviceName}
     * to ensure all modifications to a {@link WritableStates} are kept together.
     */
    @Override
    @NonNull
    public WritableStates getWritableStates(@NonNull final String serviceName) {
        if (stack.isEmpty()) {
            throw new IllegalStateException("The stack has already been committed");
        }
        return writableStatesMap.computeIfAbsent(serviceName, s -> new WritableStatesStack(this, s));
    }

    @NonNull
    @Override
    public <T extends StreamBuilder> T getBaseBuilder(@NonNull Class<T> recordBuilderClass) {
        requireNonNull(recordBuilderClass, "recordBuilderClass must not be null");
        return castBuilder(baseBuilder, recordBuilderClass);
    }

    @NonNull
    @Override
    public <T> T addChildRecordBuilder(
            @NonNull Class<T> recordBuilderClass, @NonNull final HederaFunctionality functionality) {
        requireNonNull(functionality);
        final var result = createReversibleChildBuilder().functionality(functionality);
        return castBuilder(result, recordBuilderClass);
    }

    @NonNull
    @Override
    public <T> T addRemovableChildRecordBuilder(
            @NonNull Class<T> recordBuilderClass, @NonNull final HederaFunctionality functionality) {
        requireNonNull(functionality);
        final var result = createRemovableChildBuilder().functionality(functionality);
        return castBuilder(result, recordBuilderClass);
    }

    public static <T> T castBuilder(@NonNull final StreamBuilder builder, @NonNull final Class<T> builderClass) {
        if (!builderClass.isInstance(builder)) {
            throw new IllegalArgumentException("Not a valid record builder class");
        }
        return builderClass.cast(builder);
    }

    /**
     * May only be called on the root stack to determine if this stack has capacity to create more system records
     * as preceding dispatches.
     *
     * @return whether there are more system records to be created
     * @throws NullPointerException if called on a non-root stack
     */
    public boolean rootHasPrecedingCapacity() {
        return requireNonNull(builderSink).precedingCapacity()
                > requireNonNull(stack.peekFirst()).numPreceding();
    }

    /**
     * Whether this stack has accumulated any stream builders other than its base builder; important to know when
     * determining the record finalization work to be done.
     *
     * @return whether this stack has any stream builders other than the base builder
     */
    public boolean hasNonBaseStreamBuilder() {
        if (builderSink != null && builderSink.hasBuilderOtherThan(baseBuilder)) {
            return true;
        }
        for (final var savepoint : stack) {
            if (savepoint.hasBuilderOtherThan(baseBuilder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For each stream builder in this stack other than the designated base builder, invokes the given consumer
     * with the builder cast to the given type.
     *
     * @param builderClass the type to cast the builders to
     * @param consumer     the consumer to invoke
     * @param <T>          the type to cast the builders to
     */
    public <T> void forEachNonBaseBuilder(@NonNull final Class<T> builderClass, @NonNull final Consumer<T> consumer) {
        requireNonNull(builderClass);
        requireNonNull(consumer);
        if (builderSink != null) {
            builderSink.forEachOtherBuilder(consumer, builderClass, baseBuilder);
        }
        for (var savepoint : stack) {
            savepoint.forEachOtherBuilder(consumer, builderClass, baseBuilder);
        }
    }

    /**
     * Returns a transaction ID that can safely assigned to a child in this stack's context without
     * waiting to the end of the transaction.
     *
     * @param isLastAllowed whether the stack should refuse to create more preset ids after this one
     * @return the next expected transaction ID
     * @throws HandleException      if the last allowed preset id was already created, or if the nonce
     *                              changed from negative to positive, indicating there are no more nonces left for the base id
     * @throws NullPointerException if this is called before the base builder was given an id
     */
    public TransactionID nextPresetTxnId(final boolean isLastAllowed) {
        // Child stacks always delegate such requests to their parent
        if (state instanceof SavepointStackImpl parent) {
            return parent.nextPresetTxnId(isLastAllowed);
        }
        if (!presetIdsAllowed) {
            throw new HandleException(NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION);
        }
        numPresetIds++;
        if (isLastAllowed) {
            presetIdsAllowed = false;
        }
        final var baseId = requireNonNull(baseBuilder.transactionID());
        final var presetNonce = baseId.nonce() + numPresetIds * noncesPerPresetId;
        if (baseId.nonce() < 0 && presetNonce >= 0) {
            throw new HandleException(RECURSIVE_SCHEDULING_LIMIT_REACHED);
        }
        return baseId.copyBuilder().nonce(presetNonce).build();
    }

    /**
     * Returns the {@link TransactionCategory} of the transaction that created this stack.
     *
     * @return the transaction category
     */
    public TransactionCategory txnCategory() {
        return baseBuilder.category();
    }

    /**
     * Creates a new stream builder for a removable child in the active savepoint.
     *
     * @return the new stream builder
     */
    public StreamBuilder createRemovableChildBuilder() {
        return trackAnyEnclosingBatchInner(
                peek().createBuilder(REMOVABLE, CHILD, NOOP_SIGNED_TX_CUSTOMIZER, streamMode, false));
    }

    /**
     * Creates a new stream builder for a reversible child in the active savepoint.
     *
     * @return the new stream builder
     */
    public StreamBuilder createReversibleChildBuilder() {
        return trackAnyEnclosingBatchInner(
                peek().createBuilder(REVERSIBLE, CHILD, NOOP_SIGNED_TX_CUSTOMIZER, streamMode, false));
    }

    /**
     * Creates a new stream builder for an irreversible preceding transaction in the active savepoint.
     *
     * @return the new stream builder
     */
    public StreamBuilder createIrreversiblePrecedingBuilder() {
        return trackAnyEnclosingBatchInner(
                peek().createBuilder(IRREVERSIBLE, PRECEDING, NOOP_SIGNED_TX_CUSTOMIZER, streamMode, false));
    }

    /**
     * Returns the top savepoint without removing it from the stack.
     *
     * @return the top savepoint
     * @throws IllegalStateException if the stack has been committed already
     */
    public @NonNull Savepoint peek() {
        if (stack.isEmpty()) {
            throw new IllegalStateException("The stack has already been committed");
        }
        return stack.peek();
    }

    /**
     * Builds the {@link BlockRecordSource} and/or {@link RecordSource} for this user transaction. This is only used in
     * {@link com.hedera.node.app.workflows.standalone.TransactionExecutor}
     *
     * @param consensusTime consensus time of the transaction
     * @param exchangeRates the active exchange rates
     * @return the source of records and/or blocks for the transaction
     */
    public HandleOutput buildHandleOutput(
            @NonNull final Instant consensusTime, @NonNull final ExchangeRateSet exchangeRates) {
        return buildHandleOutput(consensusTime, exchangeRates, null);
    }

    /**
     * Builds the {@link BlockRecordSource} and/or {@link RecordSource} for this user transaction.
     *
     * @param consensusTime consensus time of the transaction
     * @param exchangeRates the active exchange rates
     * @param blockNumber the block number for the produced records, if known
     * @return the source of records and/or blocks for the transaction
     */
    public HandleOutput buildHandleOutput(
            @NonNull final Instant consensusTime,
            @NonNull final ExchangeRateSet exchangeRates,
            @Nullable final Long blockNumber) {
        final List<BlockStreamBuilder.Output> outputs = streamMode != RECORDS ? new LinkedList<>() : null;
        final List<SingleTransactionRecord> records = streamMode != BLOCKS ? new ArrayList<>() : null;
        final List<RecordSource.IdentifiedReceipt> receipts = streamMode != BLOCKS ? new ArrayList<>() : null;

        var lastAssignedConsenusTime = consensusTime;
        final var builders = requireNonNull(builderSink).allBuilders();
        TransactionID.Builder idBuilder = null;
        int indexOfParentBuilder = 0;
        int topLevelNonce = 0;
        boolean grouped = false;
        final int n = builders.size();
        for (int i = 0; i < n; i++) {
            final var builder = builders.get(i);
            final var category = builder.category();
            if (category == USER || category == NODE) {
                indexOfParentBuilder = i;
                topLevelNonce = builder.transactionID().nonce();
                idBuilder = builder.transactionID().copyBuilder();
                grouped = builder.functionality() == ATOMIC_BATCH;
                break;
            }
        }

        // The consensus time of each batch inner transaction, so that a synthetic record dispatched by an inner
        // can report that inner as its parent whichever side of it the record was flushed onto; an early-flushed
        // preceding record is reached before its inner, so these cannot be discovered as we go
        final Map<TransactionID, Instant> batchInnerConsensusTimes;
        if (batchInnerIdsByBuilder == null) {
            batchInnerConsensusTimes = null;
        } else {
            batchInnerConsensusTimes = new HashMap<>();
            for (int j = 0; j < n; j++) {
                final var candidate = builders.get(j);
                if (candidate.category() == BATCH_INNER) {
                    batchInnerConsensusTimes.put(
                            candidate.transactionID(), consensusTime.plusNanos((long) j - indexOfParentBuilder));
                }
            }
        }

        int nextNonceOffset = 1;
        var parentConsensusTime = consensusTime;
        for (int i = 0; i < n; i++) {
            final var builder = builders.get(i);
            grouped |= builder.functionality() == HOOK_DISPATCH;
            final var nonceOffset =
                    switch (builder.category()) {
                        case USER, SCHEDULED, NODE, BATCH_INNER -> 0;
                        case PRECEDING, CHILD -> nextNonceOffset++;
                    };
            final var txnId = builder.transactionID();
            // If the builder does not already have a transaction id, then complete with the next nonce offset;
            // attributing it to the batch inner transaction that dispatched it, if there was one, and otherwise
            // to this stack's top-level transaction
            if (txnId == null || TransactionID.DEFAULT.equals(txnId)) {
                final var batchInnerId = batchInnerIdsByBuilder == null ? null : batchInnerIdsByBuilder.get(builder);
                final var baseIdBuilder = batchInnerId != null ? batchInnerId.copyBuilder() : requireNonNull(idBuilder);
                builder.transactionID(
                                baseIdBuilder.nonce(topLevelNonce + nonceOffset).build())
                        .syncBodyIdFromRecordId();
            }
            final var consensusNow = consensusTime.plusNanos((long) i - indexOfParentBuilder);
            lastAssignedConsenusTime = consensusNow;
            builder.consensusTimestamp(consensusNow);

            if (i > indexOfParentBuilder) {
                switch (builder.category()) {
                    // In the block stream, we _do_ set a triggered tx's parent consensus time to the scheduling
                    // transaction that triggered it; noop for streamMode=RECORDS, c.f. RecordStreamBuilder
                    case SCHEDULED -> builder.exchangeRate(exchangeRates).triggeringParentConsensus(consensusTime);
                    case BATCH_INNER -> {
                        builder.parentConsensus(consensusTime).exchangeRate(null);
                        parentConsensusTime = consensusNow;
                    }
                    case PRECEDING ->
                        builder.parentConsensus(
                                        precedingParentConsensus(builder, consensusTime, batchInnerConsensusTimes))
                                .exchangeRate(null);
                    case CHILD -> builder.parentConsensus(parentConsensusTime).exchangeRate(null);
                }
            }
            if (streamMode == BLOCKS) {
                builder.blockNumber(blockNumber);
            }

            switch (streamMode) {
                case RECORDS -> {
                    final var nextRecord = ((RecordStreamBuilder) builder).build();
                    records.add(nextRecord);
                    receipts.add(new RecordSource.IdentifiedReceipt(
                            nextRecord.transactionRecord().transactionIDOrThrow(),
                            nextRecord.transactionRecord().receiptOrThrow()));
                }
                case BLOCKS -> {
                    final var groupStateChanges = grouped ? baseBuilder.getStateChanges() : null;
                    requireNonNull(outputs)
                            .add(((BlockStreamBuilder) builder).build(builder == baseBuilder, groupStateChanges));
                }
                case BOTH -> {
                    final var pairedBuilder = (PairedStreamBuilder) builder;
                    records.add(pairedBuilder.recordStreamBuilder().build());
                    final var groupStateChanges = grouped ? baseBuilder.getStateChanges() : null;
                    requireNonNull(outputs)
                            .add(pairedBuilder.blockStreamBuilder().build(builder == baseBuilder, groupStateChanges));
                }
            }
        }
        BlockRecordSource blockRecordSource = null;
        if (streamMode != RECORDS) {
            blockRecordSource = new BlockRecordSource(outputs);
        }
        final var recordSource = streamMode != BLOCKS ? new LegacyListRecordSource(records, receipts) : null;
        return new HandleOutput(blockRecordSource, recordSource, lastAssignedConsenusTime);
    }

    /**
     * If the given builder was dispatched within the scope of an atomic batch inner transaction, records that inner
     * transaction's id as the builder's owner in the root stack; so that
     * {@link #buildHandleOutput(Instant, ExchangeRateSet, Long)} can stamp the builder with the identity of the inner
     * transaction that actually produced it.
     *
     * @param builder the builder just created in this stack
     * @return the given builder
     */
    private StreamBuilder trackAnyEnclosingBatchInner(@NonNull final StreamBuilder builder) {
        final var batchInnerId = enclosingBatchInnerTxnId();
        if (batchInnerId != null) {
            trackBatchInnerId(builder, batchInnerId);
        }
        return builder;
    }

    /**
     * Returns the transaction id of the nearest atomic batch inner transaction enclosing this stack, or null if this
     * stack is not being used within a batch inner transaction.
     */
    @Nullable
    TransactionID enclosingBatchInnerTxnId() {
        if (baseBuilder.category() == BATCH_INNER) {
            // Null only in the moment before a batch inner dispatch's base builder is initialized from its body,
            // which is not a case where an owner needs recording, since that builder has an id of its own
            return baseBuilder.transactionID();
        }
        return state instanceof SavepointStackImpl parent ? parent.enclosingBatchInnerTxnId() : null;
    }

    /**
     * Returns the consensus time a preceding builder should report as its parent; that is, the consensus time of the
     * batch inner transaction that dispatched it, or this stack's top-level consensus time if it was not dispatched
     * within a batch inner transaction.
     *
     * @param builder the preceding builder
     * @param topLevelConsensusTime the consensus time of this stack's top-level transaction
     * @param batchInnerConsensusTimes the consensus time of each batch inner transaction, if this is a batch
     * @return the consensus time to report as the builder's parent
     */
    private Instant precedingParentConsensus(
            @NonNull final StreamBuilder builder,
            @NonNull final Instant topLevelConsensusTime,
            @Nullable final Map<TransactionID, Instant> batchInnerConsensusTimes) {
        if (batchInnerIdsByBuilder == null || batchInnerConsensusTimes == null) {
            return topLevelConsensusTime;
        }
        final var batchInnerId = batchInnerIdsByBuilder.get(builder);
        if (batchInnerId == null) {
            return topLevelConsensusTime;
        }
        return batchInnerConsensusTimes.getOrDefault(batchInnerId, topLevelConsensusTime);
    }

    /**
     * Records the given builder's owning batch inner transaction id in the root stack, which is the stack that will
     * assign the builder an id of its own.
     *
     * @param builder the builder to record an owner for
     * @param batchInnerId the id of the batch inner transaction that dispatched the builder
     */
    void trackBatchInnerId(@NonNull final StreamBuilder builder, @NonNull final TransactionID batchInnerId) {
        // Child stacks always delegate such requests to their parent
        if (state instanceof SavepointStackImpl parent) {
            parent.trackBatchInnerId(builder, batchInnerId);
            return;
        }
        if (batchInnerIdsByBuilder == null) {
            batchInnerIdsByBuilder = new IdentityHashMap<>();
        }
        batchInnerIdsByBuilder.put(builder, batchInnerId);
    }

    private void setupFirstSavepoint(@NonNull final TransactionCategory category) {
        if (state instanceof SavepointStackImpl parent) {
            stack.push(new FirstChildSavepoint(new WrappedState(state), parent.peek(), category));
        } else {
            stack.push(new FirstRootSavepoint(new WrappedState(state), requireNonNull(builderSink)));
        }
    }

    private StreamBuilder createRootBaseBuilder(final int maxSerializedTraceDataBytes) {
        final var builder =
                switch (streamMode) {
                    case RECORDS ->
                        new RecordStreamBuilder(
                                REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes);
                    case BLOCKS ->
                        new BlockStreamBuilder(
                                REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes);
                    case BOTH ->
                        new PairedStreamBuilder(
                                REVERSIBLE, NOOP_SIGNED_TX_CUSTOMIZER, USER, maxSerializedTraceDataBytes);
                };
        peek().addFollowingOrThrow(builder);
        return builder;
    }

    @Override
    public void setHash(Hash hash) {
        state.setHash(hash);
    }
}
