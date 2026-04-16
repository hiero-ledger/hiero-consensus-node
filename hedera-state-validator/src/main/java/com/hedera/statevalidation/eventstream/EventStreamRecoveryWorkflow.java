// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.eventstream;

import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.hedera.pbj.runtime.ParseException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.recovery.internal.EventStreamRoundIterator;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import com.swirlds.platform.system.SwirldMain;
import com.swirlds.platform.util.HederaUtils;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pcli.recovery.EventRecoveryWorkflow;
import org.hiero.consensus.pcli.recovery.RecoveredState;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * This workflow replays events from an event stream on top of a given state and creates a new
 * snapshot once the state is advanced to the target round.
 *
 * <p>Structurally parallels {@link com.hedera.statevalidation.blockstream.BlockStreamRecoveryWorkflow}
 * but uses event files (containing transactions) rather than block files (containing pre-computed
 * state changes). Transactions are re-executed through the Hedera application layer via
 * {@link EventRecoveryWorkflow#reapplyTransactions}.
 */
public class EventStreamRecoveryWorkflow {

    private static final Logger log = LogManager.getLogger(EventStreamRecoveryWorkflow.class);

    private final long targetRound;
    private final Path outputPath;
    private final String expectedRootHash;
    private final boolean allowPartialRounds;
    private final boolean loadSigningKeys;

    public EventStreamRecoveryWorkflow(
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedRootHash,
            boolean allowPartialRounds,
            boolean loadSigningKeys) {
        this.targetRound = targetRound;
        this.outputPath = requireNonNull(outputPath);
        this.expectedRootHash = requireNonNull(expectedRootHash);
        this.allowPartialRounds = allowPartialRounds;
        this.loadSigningKeys = loadSigningKeys;
    }

    /**
     * Convenience entry point that mirrors the static
     * {@link com.hedera.statevalidation.blockstream.BlockStreamRecoveryWorkflow#applyBlocks} pattern.
     *
     * <p>Loads state from the directory indicated by the {@code state.dir} system property (set by
     * {@link com.hedera.statevalidation.StateOperatorCommand#initializeStateDir()}), reads the
     * event stream, replays transactions, and writes the resulting snapshot.
     *
     * @param eventStreamDirectory directory tree containing event stream files
     * @param selfId               the node whose keys are available locally
     * @param targetRound          last round to apply ({@code -1} = apply all)
     * @param outputPath           where the resulting snapshot is written
     * @param expectedHash         if non-empty, the expected root hash of the resulting state
     * @param allowPartialRounds   whether to allow incomplete final rounds
     * @param loadSigningKeys      whether to load signing keys for the node
     */
    public static void applyEvents(
            @NonNull final Path eventStreamDirectory,
            @NonNull final NodeId selfId,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedHash,
            boolean allowPartialRounds,
            boolean loadSigningKeys)
            throws IOException, ParseException {

        final EventStreamRecoveryWorkflow workflow =
                new EventStreamRecoveryWorkflow(targetRound, outputPath, expectedHash, allowPartialRounds, loadSigningKeys);
        workflow.replayEvents(eventStreamDirectory, selfId, getPlatformContext());
    }

    /**
     * Core workflow: loads the bootstrap state, constructs the round iterator from the event stream,
     * delegates transaction replay to {@link EventRecoveryWorkflow#reapplyTransactions}, then
     * writes the resulting state snapshot.
     */
    public void replayEvents(
            @NonNull final Path eventStreamDirectory,
            @NonNull final NodeId selfId,
            @NonNull final PlatformContext platformContext)
            throws IOException, ParseException {

        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        platformContext.getMetrics(),
                        platformContext.getTime(),
                        platformContext.getConfiguration());

        // Load the bootstrap state from the state.dir system property path
        final Path stateDir = Path.of(System.getProperty("state.dir"));
        log.info("Loading bootstrap state from {}", stateDir);

        final var deserializedSignedState =
                com.swirlds.platform.state.snapshot.SignedStateFileReader.readState(
                        stateDir, platformContext, stateLifecycleManager);

        final SwirldMain hederaApp = HederaUtils.createHederaAppMain(platformContext);
        HederaUtils.updateStateHash(hederaApp, deserializedSignedState);

        try (final ReservedSignedState initialState = deserializedSignedState.reservedSignedState()) {
            final long initRound = roundOf(initialState.get().getState());
            log.info("Bootstrap state loaded at round {}", initRound);
            log.info("Loading event stream from {}", eventStreamDirectory);

            final var roundIterator = new EventStreamRoundIterator(
                    initialState.get().getRoster(),
                    eventStreamDirectory,
                    initRound + 1,
                    allowPartialRounds);

            log.info("Replaying transactions from event stream");

            final RecoveredState recoveredState = EventRecoveryWorkflow.reapplyTransactions(
                    platformContext,
                    initialState.getAndReserve("applyEvents"),
                    hederaApp,
                    roundIterator,
                    targetRound,
                    selfId,
                    loadSigningKeys);

            final long resultRound = recoveredState.state().get().getRound();
            log.info("Replay complete at round {}, writing state to {}", resultRound, outputPath);

            // Force the state to be immutable for serialization
            stateLifecycleManager.initWithState(recoveredState.state().get().getState());

            recoveredState.state().get().getState().getHash();
            final var rootHash = requireNonNull(
                    recoveredState.state().get().getState().getHash()).getBytes();

            SignedStateFileWriter.writeSignedStateFilesToDirectory(
                    platformContext, selfId, outputPath, recoveredState.state().get(), stateLifecycleManager);

            recoveredState.state().close();
            stateLifecycleManager.getMutableState().release();
            stateLifecycleManager.getLatestImmutableState().release();

            log.info("Signed state written to disk");

            if (!expectedRootHash.isEmpty() && !expectedRootHash.equals(rootHash.toString())) {
                throw new RuntimeException(
                        "Expected and actual hashes do not match.\n Expected: %s\n Actual: %s"
                                .formatted(expectedRootHash, rootHash));
            }
        }
    }
}