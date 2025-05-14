// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import static com.swirlds.demo.consistency.ConsistencyTestingToolState.isSystemTransaction;
import static com.swirlds.logging.legacy.LogMarker.STARTUP;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * This class handles lifecycle events for the {@link ConsistencyTestingToolState}
 */
public class ConsistencyTestingToolConsensusStateEventHandler
        implements ConsensusStateEventHandler<ConsistencyTestingToolState> {

    private static final Logger logger = LogManager.getLogger(ConsistencyTestingToolState.class);

    @NonNull
    private final PlatformStateFacade platformStateFacade;

    /**
     * If not zero, and we are handling the first round after genesis, configure a freeze this duration later.
     * <p>
     * Does not affect the hash of this node (although actions may be taken based on this info that DO affect the
     * hash).
     */
    private Duration freezeAfterGenesis = null;

    public ConsistencyTestingToolConsensusStateEventHandler(@NonNull final PlatformStateFacade platformStateFacade) {
        this.platformStateFacade = platformStateFacade;
    }

    @Override
    public void onStateInitialized(
            @NonNull final ConsistencyTestingToolState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        requireNonNull(platform);
        requireNonNull(trigger);

        final StateCommonConfig stateConfig =
                platform.getContext().getConfiguration().getConfigData(StateCommonConfig.class);
        final ConsistencyTestingToolConfig testingToolConfig =
                platform.getContext().getConfiguration().getConfigData(ConsistencyTestingToolConfig.class);

        final Path logFileDirectory = stateConfig
                .savedStateDirectory()
                .resolve(testingToolConfig.logfileDirectory())
                .resolve(Long.toString(platform.getSelfId().id()));
        try {
            Files.createDirectories(logFileDirectory);
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to set up file system for consistency data", e);
        }
        final Path logFilePath = logFileDirectory.resolve("ConsistencyTestLog.csv");

        this.freezeAfterGenesis = testingToolConfig.freezeAfterGenesis();

        state.initState(logFilePath);

        TestingAppStateInitializer.DEFAULT.initStates(state);
    }

    /**
     * Modifies the state based on each transaction in the round
     * <p>
     * Writes the round and its contents to a log on disk
     */
    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull ConsistencyTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        requireNonNull(round);
        requireNonNull(state);
        if (state.getRoundsHandled() == 0 && !freezeAfterGenesis.equals(Duration.ZERO)) {
            // This is the first round after genesis.
            logger.info(
                    STARTUP.getMarker(),
                    "Setting freeze time to {} seconds after genesis.",
                    freezeAfterGenesis.getSeconds());
            platformStateFacade.bulkUpdateOf(state, v -> {
                v.setFreezeTime(round.getConsensusTimestamp().plus(freezeAfterGenesis));
            });
        }

        state.processTransactions(round, stateSignatureTransactionCallback);
    }

    /**
     * Keeps track of which transactions have been prehandled.
     */
    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull ConsistencyTestingToolState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        event.forEachTransaction(transaction -> {
            if (isSystemTransaction(transaction)) {
                state.consumeSystemTransaction(transaction, event, stateSignatureTransactionCallback);
                return;
            }

            state.processPrehandle(transaction);
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull ConsistencyTestingToolState state) {
        // no-op
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onUpdateWeight(
            @NonNull ConsistencyTestingToolState state,
            @NonNull AddressBook configAddressBook,
            @NonNull PlatformContext context) {
        // no-op
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNewRecoveredState(@NonNull ConsistencyTestingToolState recoveredState) {
        // no-op
    }
}
