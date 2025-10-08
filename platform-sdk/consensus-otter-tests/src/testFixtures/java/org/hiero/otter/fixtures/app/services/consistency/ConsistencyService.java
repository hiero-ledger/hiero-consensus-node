// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.OtterAppState;
import org.hiero.otter.fixtures.app.OtterService;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.app.state.OtterServiceStateSpecification;
import org.jetbrains.annotations.NotNull;

/**
 * A service that ensures the consistency of rounds and transactions sent by the platform to the execution layer for
 * handling. It checks these aspects of consistency:
 * <ol>
 *     <li>Consensus rounds increase in number monotonically</li>
 *     <li>Consensus rounds are received only once</li>
 *     <li>Differences in rounds or transactions recorded in the {@link ConsistencyServiceRoundHistory} on different nodes will cause an ISS</li>
 *     <li>Transactions are pre-handled only once</li>
 *     <li>Consensus transactions were previously received in pre-handle</li>
 *     <li>After a restart, any rounds that reach consensus during PCES replay exactly match the rounds calculated previously.</li>
 * </ol>
 */
public class ConsistencyService implements OtterService {

    private static final Logger log = LogManager.getLogger();

    /** The name of this service. */
    public static final String NAME = "ConsistencyStateService";

    private static final ConsistencyStateSpecification STATE_SPECIFICATION = new ConsistencyStateSpecification();

    /** A set of transaction nonce values seen in pre-handle that have not yet been handled. */
    private final Set<Long> transactionsAwaitingHandle = ConcurrentHashMap.newKeySet();

    /** A history of all rounds and transaction nonce values contained within. */
    private final ConsistencyServiceRoundHistory roundHistory = new ConsistencyServiceRoundHistory();

    /** The round number of the previous round handled. */
    private long previousRoundHandled = ConsensusConstants.ROUND_UNDEFINED;

    /**
     * {@inheritDoc}
     */
    public void initialize(
            @NonNull final InitTrigger trigger,
            @NonNull final NodeId selfId,
            @NonNull final Configuration configuration,
            @NonNull final OtterAppState state) {
        if (trigger != InitTrigger.GENESIS && trigger != InitTrigger.RESTART) {
            return;
        }
        final StateCommonConfig stateConfig = configuration.getConfigData(StateCommonConfig.class);
        final ConsistencyServiceConfig consistencyServiceConfig =
                configuration.getConfigData(ConsistencyServiceConfig.class);

        final Path historyFileDirectory = stateConfig
                .savedStateDirectory()
                .resolve(consistencyServiceConfig.historyFileDirectory())
                .resolve(Long.toString(selfId.id()));
        try {
            Files.createDirectories(historyFileDirectory);
        } catch (final IOException e) {
            log.error(EXCEPTION.getMarker(), "Unable to create log file directory", e);
            throw new UncheckedIOException("unable to set up file system for consistency data", e);
        }

        final Path historyFilePath = historyFileDirectory.resolve(consistencyServiceConfig.historyFileName());
        roundHistory.init(historyFilePath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void destroy() {
        roundHistory.close();
    }

    /**
     * Records the contents of all rounds, even empty ones. This method calculates a running checksum that includes the
     * round number and all transactions and stores the number of rounds handled in the state.
     *
     * @param writableStates the writable states used to modify the consistency state
     * @param round the round to handle
     */
    @Override
    public void onRoundStart(@NonNull final WritableStates writableStates, @NonNull final Round round) {
        verifyRoundIncreases(round);

        final WritableConsistencyStateStore store = new WritableConsistencyStateStore(writableStates)
                .accumulateRunningChecksum(round.getRoundNum())
                .incrementRoundsHandled();
        roundHistory.onRoundStart(round, store.getRunningChecksum());
    }

    private void verifyRoundIncreases(@NonNull final Round round) {
        if (previousRoundHandled == ConsensusConstants.ROUND_UNDEFINED) {
            previousRoundHandled = round.getRoundNum();
            return;
        }

        final long newRoundNumber = round.getRoundNum();

        // make sure round numbers always increase
        if (newRoundNumber <= previousRoundHandled) {
            final String error = "Round " + newRoundNumber + " is not greater than round " + previousRoundHandled;
            log.error(EXCEPTION.getMarker(), error);
        }

        previousRoundHandled = round.getRoundNum();
    }

    /**
     * This method updates the running hash that includes the contents of all transactions.
     */
    @Override
    public void handleTransaction(
            @NonNull final WritableStates writableStates,
            @NonNull final Event event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        final long transactionNonce = transaction.getNonce();
        new WritableConsistencyStateStore(writableStates).accumulateRunningChecksum(transactionNonce);
        if (!transactionsAwaitingHandle.remove(transactionNonce)) {
            log.error(EXCEPTION.getMarker(), "Transaction {} was not pre-handled.", transactionNonce);
        }
        roundHistory.onTransaction(transaction);
    }

    /**
     * This method records the checksum of all transactions that are pre-handled, so that we can verify that all
     * consensus transactions were previously pre-handled.
     *
     * @param event the event that contains the transaction
     * @param transaction the transaction being pre-handled
     * @param callback a callback to pass any system transactions to be handled by the platform
     */
    @Override
    public void preHandleTransaction(
            @NonNull final Event event,
            @NonNull final OtterTransaction transaction,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        final long transactionNonce = transaction.getNonce();
        if (!transactionsAwaitingHandle.add(transactionNonce)) {
            log.error(EXCEPTION.getMarker(), "Transaction {} was pre-handled more than once.", transactionNonce);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRoundComplete(@NotNull final Round round) {
        roundHistory.onRoundComplete();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public OtterServiceStateSpecification stateSpecification() {
        return STATE_SPECIFICATION;
    }
}
