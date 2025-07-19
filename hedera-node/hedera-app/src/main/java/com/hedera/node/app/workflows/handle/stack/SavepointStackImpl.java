// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.stack;

import static com.hedera.hapi.node.base.HederaFunctionality.ATOMIC_BATCH;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_SCHEDULING_ALLOWED_AFTER_SCHEDULED_RECURSION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.RECURSIVE_SCHEDULING_LIMIT_REACHED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.PRECEDING;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.SCHEDULED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.USER;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.IRREVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REMOVABLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.ReversingBehavior.REVERSIBLE;
import static com.hedera.node.app.spi.workflows.record.StreamBuilder.TransactionCustomizer.NOOP_TRANSACTION_CUSTOMIZER;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.ExchangeRateSet;
import com.hedera.node.app.blocks.impl.BlockStreamBuilder;
import com.hedera.node.app.blocks.impl.BoundaryStateChangeListener;
import com.hedera.node.app.blocks.impl.KVStateChangeListener;
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
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.metrics.api.Metrics;
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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
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

    @Nullable
    private final KVStateChangeListener kvStateChangeListener;

    @Nullable
    private final BoundaryStateChangeListener boundaryStateChangeListener;

    private final StreamMode streamMode;

    private int numPresetIds;
    private int noncesToSkipPerPresetId;
    private boolean presetIdsAllowed;

    /**
     * Constructs the root {@link SavepointStackImpl} for the given state at the start of handling a user transaction.
     *
     * @param state                       the state
     * @param maxBuildersBeforeUser       the maximum number of preceding builders with available consensus times
     * @param maxBuildersAfterUser        the maximum number of following builders with available consensus times
     * @param boundaryStateChangeListener the listener for the round state changes
     * @param kvStateChangeListener       the listener for the key/value state changes
     * @param streamMode                  the stream mode
     * @return the root {@link SavepointStackImpl}
     */
    public static SavepointStackImpl newRootStack(
            @NonNull final State state,
            final int maxBuildersBeforeUser,
            final int maxBuildersAfterUser,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final KVStateChangeListener kvStateChangeListener,
            @NonNull final StreamMode streamMode) {
        return new SavepointStackImpl(
                state,
                maxBuildersBeforeUser,
                maxBuildersAfterUser,
                boundaryStateChangeListener,
                kvStateChangeListener,
                streamMode);
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
            @NonNull final StreamBuilder.TransactionCustomizer customizer,
            @NonNull final StreamMode streamMode) {
        return new SavepointStackImpl(root, reversingBehavior, category, customizer, streamMode);
    }

    /**
     * Constructs a new root {@link SavepointStackImpl} with the given root state.
     *
     * @param state                    the state
     * @param maxBuildersBeforeUser    the maximum number of preceding builders to create
     * @param maxBuildersAfterUser     the maximum number of following builders to create
     * @param boundaryStateChangeListener the listener for the round state changes
     * @param kvStateChangeListener    the listener for the key-value state changes
     * @param streamMode               the stream mode
     */
    private SavepointStackImpl(
            @NonNull final State state,
            final int maxBuildersBeforeUser,
            final int maxBuildersAfterUser,
            @NonNull final BoundaryStateChangeListener boundaryStateChangeListener,
            @NonNull final KVStateChangeListener kvStateChangeListener,
            @NonNull final StreamMode streamMode) {
        this.state = requireNonNull(state);
        this.kvStateChangeListener = requireNonNull(kvStateChangeListener);
        this.boundaryStateChangeListener = requireNonNull(boundaryStateChangeListener);
        builderSink = new BuilderSinkImpl(maxBuildersBeforeUser, maxBuildersAfterUser + 1);
        presetIdsAllowed = true;
        noncesToSkipPerPresetId = maxBuildersBeforeUser + maxBuildersAfterUser;
        setupFirstSavepoint(USER);
        baseBuilder = peek().createBuilder(REVERSIBLE, USER, NOOP_TRANSACTION_CUSTOMIZER, streamMode, true);
        this.streamMode = requireNonNull(streamMode);
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
            @NonNull final StreamBuilder.TransactionCustomizer customizer,
            @NonNull final StreamMode streamMode) {
        requireNonNull(reversingBehavior);
        requireNonNull(customizer);
        requireNonNull(category);
        this.streamMode = requireNonNull(streamMode);
        this.state = requireNonNull(parent);
        this.builderSink = null;
        this.kvStateChangeListener = null;
        this.boundaryStateChangeListener = null;
        setupFirstSavepoint(category);
        baseBuilder = peek().createBuilder(reversingBehavior, category, customizer, streamMode, true);
        presetIdsAllowed = false;
    }

    @Override
    public void init(Time time, Metrics metrics, MerkleCryptography merkleCryptography, LongSupplier roundSupplier) {
        state.init(time, metrics, merkleCryptography, roundSupplier);
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
        if (streamMode != RECORDS && kvStateChangeListener != null) {
            kvStateChangeListener.reset();
        }
        while (!stack.isEmpty()) {
            final var savepoint = stack.pop();
            // If this is a root stack, track the collected node fees for the block
            if (boundaryStateChangeListener != null && stack.isEmpty()) {
                boundaryStateChangeListener.trackCollectedNodeFees(savepoint.getNodeFeesCollected());
            }
            savepoint.commit();
        }
        if (streamMode != RECORDS && kvStateChangeListener != null) {
            builder.stateChanges(kvStateChangeListener.getStateChanges());
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
     * May only be called on the root stack to determine if this stack has capacity to create more system records to
     * as preceding dispatches.
     *
     * @return whether there are more system records to be created
     * @throws NullPointerException if called on a non-root stack
     */
    public boolean hasMoreSystemRecords() {
        return requireNonNull(builderSink).precedingCapacity() > 0;
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
        final var presetNonce = baseId.nonce() + numPresetIds * noncesToSkipPerPresetId;
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
        return peek().createBuilder(REMOVABLE, CHILD, NOOP_TRANSACTION_CUSTOMIZER, streamMode, false);
    }

    /**
     * Creates a new stream builder for a reversible child in the active savepoint.
     *
     * @return the new stream builder
     */
    public StreamBuilder createReversibleChildBuilder() {
        return peek().createBuilder(REVERSIBLE, CHILD, NOOP_TRANSACTION_CUSTOMIZER, streamMode, false);
    }

    /**
     * Creates a new stream builder for an irreversible preceding transaction in the active savepoint.
     *
     * @return the new stream builder
     */
    public StreamBuilder createIrreversiblePrecedingBuilder() {
        return peek().createBuilder(IRREVERSIBLE, PRECEDING, NOOP_TRANSACTION_CUSTOMIZER, streamMode, false);
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
     * Builds the {@link BlockRecordSource} and/or {@link RecordSource} for this user transaction.
     *
     * @param consensusTime consensus time of the transaction
     * @param exchangeRates the active exchange rates
     * @return the source of records and/or blocks for the transaction
     */
    public HandleOutput buildHandleOutput(
            @NonNull final Instant consensusTime, @NonNull final ExchangeRateSet exchangeRates) {
        final List<BlockStreamBuilder.Output> outputs = streamMode != RECORDS ? new LinkedList<>() : null;
        final List<SingleTransactionRecord> records = streamMode != BLOCKS ? new ArrayList<>() : null;
        final List<RecordSource.IdentifiedReceipt> receipts = streamMode != BLOCKS ? new ArrayList<>() : null;

        var lastAssignedConsenusTime = consensusTime;
        final var builders = requireNonNull(builderSink).allBuilders();
        TransactionID.Builder idBuilder = null;
        int indexOfParentBuilder = 0;
        int topLevelNonce = 0;
        boolean isBatch = false;
        final int n = builders.size();
        for (int i = 0; i < n; i++) {
            final var builder = builders.get(i);
            final var category = builder.category();
            if (category == USER || category == NODE) {
                indexOfParentBuilder = i;
                topLevelNonce = builder.transactionID().nonce();
                idBuilder = builder.transactionID().copyBuilder();
                isBatch = builder.functionality() == ATOMIC_BATCH;
                break;
            }
        }
        int nextNonceOffset = 1;
        var parentConsensusTime = consensusTime;
        for (int i = 0; i < n; i++) {
            final var builder = builders.get(i);
            final var nonceOffset =
                    switch (builder.category()) {
                        case USER, SCHEDULED, NODE, BATCH_INNER -> 0;
                        case PRECEDING, CHILD -> nextNonceOffset++;
                    };
            final var txnId = builder.transactionID();
            // If the builder does not already have a transaction id, then complete with the next nonce offset
            if (txnId == null || TransactionID.DEFAULT.equals(txnId)) {
                if (i > indexOfParentBuilder && isBatch) {
                    if (builder.category() == PRECEDING) {
                        for (int j = i + 1; j < n; j++) {
                            if (builders.get(j).category() == BATCH_INNER) {
                                idBuilder = builders.get(j).transactionID().copyBuilder();
                                break;
                            }
                        }
                    } else if (builder.category() == CHILD) {
                        for (int j = i - 1; j > indexOfParentBuilder; j--) {
                            if (builders.get(j).category() == BATCH_INNER) {
                                idBuilder = builders.get(j).transactionID().copyBuilder();
                                break;
                            }
                        }
                    }
                }
                builder.transactionID(requireNonNull(idBuilder)
                                .nonce(topLevelNonce + nonceOffset)
                                .build())
                        .syncBodyIdFromRecordId();
            }
            final var consensusNow = consensusTime.plusNanos((long) i - indexOfParentBuilder);
            lastAssignedConsenusTime = consensusNow;
            builder.consensusTimestamp(consensusNow);

            if (i > indexOfParentBuilder) {
                switch (builder.category()) {
                    case SCHEDULED -> builder.exchangeRate(exchangeRates);
                    case BATCH_INNER -> {
                        builder.parentConsensus(consensusTime).exchangeRate(null);
                        parentConsensusTime = consensusNow;
                    }
                    case PRECEDING -> builder.parentConsensus(consensusTime).exchangeRate(null);
                    case CHILD -> builder.parentConsensus(parentConsensusTime).exchangeRate(null);
                }
            }

            // Add trace data for batch inner (or inner child) transaction fields, that are normally computed by state
            // changes
            final boolean addAdditionalTraceData = baseBuilder.functionality() == ATOMIC_BATCH;
            switch (streamMode) {
                case RECORDS -> {
                    final var nextRecord = ((RecordStreamBuilder) builder).build();
                    records.add(nextRecord);
                    receipts.add(new RecordSource.IdentifiedReceipt(
                            nextRecord.transactionRecord().transactionIDOrThrow(),
                            nextRecord.transactionRecord().receiptOrThrow()));
                }
                case BLOCKS ->
                    requireNonNull(outputs)
                            .add(((BlockStreamBuilder) builder).build(builder == baseBuilder, addAdditionalTraceData));
                case BOTH -> {
                    final var pairedBuilder = (PairedStreamBuilder) builder;
                    records.add(pairedBuilder.recordStreamBuilder().build());
                    requireNonNull(outputs)
                            .add(pairedBuilder
                                    .blockStreamBuilder()
                                    .build(builder == baseBuilder, addAdditionalTraceData));
                }
            }
        }
        BlockRecordSource blockRecordSource = null;
        if (streamMode != RECORDS) {
            requireNonNull(boundaryStateChangeListener).setBoundaryTimestamp(lastAssignedConsenusTime);
            blockRecordSource = new BlockRecordSource(outputs);
        }
        final var recordSource = streamMode != BLOCKS ? new LegacyListRecordSource(records, receipts) : null;
        final var firstAssignedConsensusTime =
                indexOfParentBuilder == 0 ? consensusTime : consensusTime.minusNanos(indexOfParentBuilder);
        return new HandleOutput(blockRecordSource, recordSource, firstAssignedConsensusTime);
    }

    private void setupFirstSavepoint(@NonNull final TransactionCategory category) {
        if (state instanceof SavepointStackImpl parent) {
            stack.push(new FirstChildSavepoint(new WrappedState(state), parent.peek(), category));
        } else {
            stack.push(new FirstRootSavepoint(new WrappedState(state), requireNonNull(builderSink)));
        }
    }

    @Override
    public void setHash(Hash hash) {
        state.setHash(hash);
    }
}
