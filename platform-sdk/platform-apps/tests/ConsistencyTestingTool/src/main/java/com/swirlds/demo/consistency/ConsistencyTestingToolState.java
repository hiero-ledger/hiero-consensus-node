// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.demo.consistency.V0680ConsistencyTestingToolSchema.CONSISTENCY_SERVICE_NAME;
import static com.swirlds.demo.consistency.V0680ConsistencyTestingToolSchema.ROUND_HANDLED_STATE_ID;
import static com.swirlds.demo.consistency.V0680ConsistencyTestingToolSchema.STATE_LONG_STATE_ID;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static org.hiero.base.utility.ByteUtils.byteArrayToLong;
import static org.hiero.base.utility.NonCryptographicHashing.hash64;

import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.disk.OnDiskWritableSingletonState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;

/**
 * State for the Consistency Testing Tool
 */
public class ConsistencyTestingToolState extends VirtualMapState<ConsistencyTestingToolState>
        implements MerkleNodeState {

    private static final Logger logger = LogManager.getLogger(ConsistencyTestingToolState.class);

    /**
     * The true "state" of this app. This long value is updated with every transaction, and with every round.
     * <p>
     * Affects the hash of this node.
     */
    private long stateLong = 0;

    /**
     * The number of rounds handled by this app. Is incremented each time
     * {@link ConsistencyTestingToolConsensusStateEventHandler#onHandleConsensusRound(Round, ConsistencyTestingToolState, Consumer)} is called. Note that this may not actually equal the round
     * number, since we don't call {@link ConsistencyTestingToolConsensusStateEventHandler#onHandleConsensusRound(Round, ConsistencyTestingToolState, Consumer<ScopedSystemTransaction<StateSignatureTransaction>>)} for rounds with no events.
     *
     * <p>
     * Affects the hash of this node.
     */
    private long roundsHandled = 0;

    /**
     * The history of transactions that have been handled by this app.
     * <p>
     * A deep copy of this object is NOT created when this state is copied. This object does not affect the hash of this
     * node.
     */
    private final TransactionHandlingHistory transactionHandlingHistory;

    /**
     * The set of transactions that have been preconsensus-handled by this app, but haven't yet been
     * postconsensus-handled. This is used to ensure that transactions are prehandled exactly 1 time, prior to
     * posthandling.
     * <p>
     * Does not affect the hash of this node.
     */
    private final Set<Long> transactionsAwaitingPostHandle;

    public ConsistencyTestingToolState(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(configuration, metrics, time);
        transactionHandlingHistory = new TransactionHandlingHistory();
        transactionsAwaitingPostHandle = ConcurrentHashMap.newKeySet();
        logger.info(STARTUP.getMarker(), "New State Constructed.");
    }

    /**
     * Constructor
     */
    public ConsistencyTestingToolState(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        super(virtualMap, metrics, time);
        transactionHandlingHistory = new TransactionHandlingHistory();
        transactionsAwaitingPostHandle = ConcurrentHashMap.newKeySet();
        logger.info(STARTUP.getMarker(), "New State Constructed.");
    }

    /**
     * Copy constructor
     *
     * @param that the state to copy
     */
    private ConsistencyTestingToolState(@NonNull final ConsistencyTestingToolState that) {
        super(Objects.requireNonNull(that));
        this.stateLong = that.stateLong;
        this.roundsHandled = that.roundsHandled;
        this.transactionHandlingHistory = that.transactionHandlingHistory;
        this.transactionsAwaitingPostHandle = that.transactionsAwaitingPostHandle;
    }

    @Override
    protected ConsistencyTestingToolState copyingConstructor() {
        return new ConsistencyTestingToolState(this);
    }

    @Override
    protected ConsistencyTestingToolState newInstance(
            @NonNull final VirtualMap virtualMap, @NonNull final Metrics metrics, @NonNull final Time time) {
        return new ConsistencyTestingToolState(virtualMap, metrics, time);
    }

    /**
     * Initialize the state
     */
    void initState(Path logFilePath) {
        final var schema = new V0680ConsistencyTestingToolSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    super.initializeState(new StateMetadata<>(CONSISTENCY_SERVICE_NAME, def));
                });

        final ReadableStates readableStates = getReadableStates(CONSISTENCY_SERVICE_NAME);

        final ReadableSingletonState<ProtoLong> stateLongState = readableStates.getSingleton(STATE_LONG_STATE_ID);
        final ProtoLong stateLongProto = stateLongState.get();
        if (stateLongProto != null) {
            this.stateLong = stateLongProto.value();
            logger.info(STARTUP.getMarker(), "State initialized with state long {}.", stateLong);
        }

        final ReadableSingletonState<ProtoLong> roundsHandledState =
                readableStates.getSingleton(ROUND_HANDLED_STATE_ID);
        final ProtoLong roundsHandledProto = roundsHandledState.get();
        if (roundsHandledProto != null) {
            this.roundsHandled = roundsHandledProto.value();
            logger.info(STARTUP.getMarker(), "State initialized with {} rounds handled.", roundsHandled);
        }

        transactionHandlingHistory.init(logFilePath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected long getRound() {
        return DEFAULT_PLATFORM_STATE_FACADE.roundOf(this);
    }

    /**
     * @return the number of rounds handled
     */
    long getRoundsHandled() {
        return roundsHandled;
    }

    /**
     * Increment the number of rounds handled
     */
    void incrementRoundsHandled() {
        roundsHandled++;

        final WritableSingletonState<ProtoLong> roundsHandledState =
                getWritableStates(CONSISTENCY_SERVICE_NAME).getSingleton(ROUND_HANDLED_STATE_ID);
        roundsHandledState.put(new ProtoLong(roundsHandled));
        ((OnDiskWritableSingletonState<ProtoLong>) roundsHandledState).commit();
    }

    /**
     * @return the state represented by a long
     */
    long getStateLong() {
        return stateLong;
    }

    /**
     * Sets the state
     * @param stateLong state represented by a long
     */
    void setStateLong(final long stateLong) {
        this.stateLong = stateLong;

        final WritableSingletonState<ProtoLong> stateLongState =
                getWritableStates(CONSISTENCY_SERVICE_NAME).getSingleton(STATE_LONG_STATE_ID);
        stateLongState.put(new ProtoLong(stateLong));
        ((OnDiskWritableSingletonState<ProtoLong>) stateLongState).commit();
    }

    private void processRound(Round round) {
        stateLong = hash64(stateLong, round.getRoundNum());
        transactionHandlingHistory.processRound(ConsistencyTestingToolRound.fromRound(round, stateLong));

        setStateLong(stateLong);
    }

    void processTransactions(
            Round round,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransaction) {
        incrementRoundsHandled();

        round.forEachEventTransaction((ev, tx) -> {
            if (isSystemTransaction(tx)) {
                consumeSystemTransaction(tx, ev, stateSignatureTransaction);
            } else {
                applyTransactionToState(tx);
            }
        });

        processRound(round);
    }

    void processPrehandle(Transaction transaction) {
        final long transactionContents = getTransactionContents(transaction);
        if (!transactionsAwaitingPostHandle.add(transactionContents)) {
            logger.error(EXCEPTION.getMarker(), "Transaction {} was prehandled more than once.", transactionContents);
        }
    }

    /**
     * Sets the new {@link ConsistencyTestingToolState#stateLong} to the non-cryptographic hash of the existing state, and the contents of the
     * transaction being handled
     *
     * @param transaction the transaction to apply to the state
     */
    private void applyTransactionToState(final @NonNull ConsensusTransaction transaction) {
        Objects.requireNonNull(transaction);
        final long transactionContents = getTransactionContents(transaction);

        if (!transactionsAwaitingPostHandle.remove(transactionContents)) {
            logger.error(EXCEPTION.getMarker(), "Transaction {} was not prehandled.", transactionContents);
        }

        stateLong = hash64(stateLong, transactionContents);
        setStateLong(stateLong);
    }

    private static long getTransactionContents(Transaction transaction) {
        return byteArrayToLong(transaction.getApplicationTransaction().toByteArray(), 0);
    }

    /**
     * Determines if the given transaction is a system transaction for this app.
     *
     * @param transaction the transaction to check
     * @return true if the transaction is a system transaction, false otherwise
     */
    static boolean isSystemTransaction(final @NonNull Transaction transaction) {
        return transaction.getApplicationTransaction().length() > 8;
    }

    void consumeSystemTransaction(
            final @NonNull Transaction transaction,
            final @NonNull Event event,
            final @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>>
                            stateSignatureTransactionCallback) {
        try {
            final var stateSignatureTransaction =
                    StateSignatureTransaction.PROTOBUF.parse(transaction.getApplicationTransaction());
            stateSignatureTransactionCallback.accept(new ScopedSystemTransaction<>(
                    event.getCreatorId(), event.getBirthRound(), stateSignatureTransaction));
        } catch (final ParseException e) {
            logger.error("Failed to parse StateSignatureTransaction", e);
        }
    }
}
