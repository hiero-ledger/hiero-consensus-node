// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.ApplyBlocksCommand.DEFAULT_TARGET_ROUND;
import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import com.swirlds.state.BinaryState;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.hiero.consensus.crypto.ConsensusCryptoUtils;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.signed.SignedState;

/**
 * This workflow applies a set of blocks to a given state and creates a new snapshot once the state
 * is advanced to the target round
 */
public class BlockStreamRecoveryWorkflow {

    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private final long targetRound;
    private final Path outputPath;
    private final String expectedRootHash;

    // TEMP DIAGNOSTIC — remove after measuring. The round at/around which to dump the throttle snapshot.
    // Set to the round whose header lives in block 104401562 (the first divergent block). To see the
    // trajectory, the loop below also dumps the two preceding rounds.
    private static final long DUMP_ROUND = 253743405; /* FILL IN: round of block 104401562 */

    // State ID for CongestionThrottleService.THROTTLE_USAGE_SNAPSHOTS singleton.
    private static final int THROTTLE_USAGE_SNAPSHOTS_STATE_ID =
            SingletonType.CONGESTIONTHROTTLESERVICE_I_THROTTLE_USAGE_SNAPSHOTS.protoOrdinal();
    // Signature of the last throttle snapshot we printed; null until the first print. Instance field so it
    // resets per run. (applyBlocks is a single-threaded forEach, so no synchronization needed.)
    private String lastPrintedThrottleSig = null;

    public BlockStreamRecoveryWorkflow(
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedRootHash) {
        this.stateLifecycleManager = stateLifecycleManager;
        this.targetRound = targetRound;
        this.outputPath = outputPath;
        this.expectedRootHash = expectedRootHash;
    }

    public static void applyBlocks(
            @NonNull final Path blockStreamDirectory,
            @NonNull final NodeId selfId,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedHash)
            throws IOException {

        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        getPlatformContext().getMetrics(),
                        getPlatformContext().getTime(),
                        getPlatformContext().getConfiguration());

        stateLifecycleManager.initWithState(StateUtils.getDefaultState());
        final var blocks = BlockStreamAccess.readBlocks(blockStreamDirectory, false);
        final BlockStreamRecoveryWorkflow workflow =
                new BlockStreamRecoveryWorkflow(stateLifecycleManager, targetRound, outputPath, expectedHash);
        workflow.applyBlocks(blocks, selfId, getPlatformContext());
    }

    public void applyBlocks(
            @NonNull final Stream<Block> blocks,
            @NonNull final NodeId selfId,
            @NonNull final PlatformContext platformContext) {
        AtomicBoolean foundStartingRound = new AtomicBoolean();
        final VirtualMapState state = stateLifecycleManager.getMutableState();
        final long initRound = roundOf(state);
        final long firstRoundToApply = initRound + 1;
        AtomicLong currentRound = new AtomicLong(initRound);

        blocks.forEach(block -> {
            for (final BlockItem item : block.items()) {
                // if the first block item belongs to the round after the first round to apply, we can't proceed
                // as the block stream is incomplete
                if (!foundStartingRound.get()
                        && item.hasRoundHeader()
                        && item.roundHeader().roundNumber() > firstRoundToApply) {
                    throw new RuntimeException(
                            ("Given blockstream doesn't have a proper starting round. Must have a block item with a round = %d. "
                                    + "The oldest round found is %d")
                                    .formatted(
                                            firstRoundToApply,
                                            item.roundHeader().roundNumber()));
                }

                foundStartingRound.set(foundStartingRound.get()
                        || (item.hasRoundHeader() && item.roundHeader().roundNumber() == firstRoundToApply));

                // skip forward to the starting round
                if (!foundStartingRound.get()) {
                    continue;
                }

                // do not go beyond the target round
                if (item.hasRoundHeader()) {
                    long itemRound = item.roundHeader().roundNumber();
                    if (itemRound > targetRound) {
                        return;
                    } else {
                        if (itemRound != currentRound.get() + 1) {
                            throw new RuntimeException("Unexpected round number. Expected = %d, actual = %d"
                                    .formatted(currentRound.get() + 1, itemRound));
                        }
                        currentRound.incrementAndGet();
                    }
                }

                // ---- TEMP DIAGNOSTIC: dump throttle snapshot at end of the just-completed round ----
                final long justCompletedRound = currentRound.get() - 1;
                if (justCompletedRound >= DUMP_ROUND - 2 && justCompletedRound <= DUMP_ROUND) {
                    dumpThrottleSnapshotIfChanged(state, justCompletedRound);
                }
                // ---- END TEMP DIAGNOSTIC ----

                if (item.hasStateChanges()) {
                    applyStateChanges(item.stateChangesOrThrow());
                }
            }
        });

        if (targetRound != DEFAULT_TARGET_ROUND && currentRound.get() != targetRound) {
            throw new RuntimeException(
                    "Block stream is incomplete. Expected target round is %d, last applied round is %d"
                            .formatted(targetRound, currentRound.get()));
        }

        // To make sure that VirtualMapMetadata is persisted after all changes from the block stream were applied
        stateLifecycleManager.copyMutableState();
        state.getHash();
        final var rootHash = requireNonNull(state.getHash()).getBytes();

        final SignedState signedState = new SignedState(
                platformContext.getConfiguration(),
                ConsensusCryptoUtils::verifySignature,
                state,
                "BlockStreamWorkflow.applyBlocks()",
                false,
                false,
                false);

        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        platformContext.getMetrics(), platformContext.getTime(), platformContext.getConfiguration());
        try {
            SignedStateFileWriter.writeSignedStateFilesToDirectory(
                    platformContext, selfId, outputPath, signedState, stateLifecycleManager);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!expectedRootHash.isEmpty() && !expectedRootHash.equals(rootHash.toString())) {
            throw new RuntimeException("Excepted and actual hashes do not match. \n Expected: %s \n Actual: %s "
                    .formatted(expectedRootHash, rootHash));
        }
    }
    // TEMP DIAGNOSTIC — remove after measuring. Prints only when the throttle content differs from the
// last printed line, so the output is the sequence of DISTINCT throttle states with the round each
// first appeared at. That makes the round where GAS 'used' first moves obvious.
    private void dumpThrottleSnapshotIfChanged(final VirtualMapState state, final long round) {
        try {
            final Bytes raw = ((BinaryState) state).getSingleton(THROTTLE_USAGE_SNAPSHOTS_STATE_ID);
            if (raw == null) {
                final String sig = "absent";
                if (!sig.equals(lastPrintedThrottleSig)) {
                    lastPrintedThrottleSig = sig;
                    System.out.printf("[THROTTLE-DUMP] round=%d  <singleton absent>%n", round);
                }
                return;
            }
            final ThrottleUsageSnapshots snap = ThrottleUsageSnapshots.PROTOBUF.parse(raw);
            final ThrottleUsageSnapshot gas = snap.hasGasThrottle() ? snap.gasThrottleOrThrow() : null;
            final ThrottleUsageSnapshot ops =
                    snap.hasEvmOpsDurationThrottle() ? snap.evmOpsDurationThrottleOrThrow() : null;

            final String gasUsed = gas == null ? "none" : Long.toString(gas.used());
            final String gasTime = gas == null ? "none" : String.valueOf(gas.lastDecisionTime());
            final String opsUsed = ops == null ? "none" : Long.toString(ops.used());
            final String opsTime = ops == null ? "none" : String.valueOf(ops.lastDecisionTime());

            // Content signature — deliberately EXCLUDES the round so identical throttle state across
            // consecutive rounds collapses to a single printed line.
            final String sig = "g=" + gasUsed + "@" + gasTime + "|o=" + opsUsed + "@" + opsTime;
            if (!sig.equals(lastPrintedThrottleSig)) {
                lastPrintedThrottleSig = sig;
                System.out.printf(
                        "[THROTTLE-DUMP] round=%d  GAS{used=%s, lastDecision=%s}  OPS{used=%s, lastDecision=%s}%n",
                        round, gasUsed, gasTime, opsUsed, opsTime);
            }
        } catch (final Exception e) {
            final String sig = "error:" + e.getMessage();
            if (!sig.equals(lastPrintedThrottleSig)) {
                lastPrintedThrottleSig = sig;
                System.out.printf("[THROTTLE-DUMP] round=%d  <error: %s>%n", round, e.getMessage());
            }
        }
    }

    private void applyStateChanges(@NonNull final StateChanges stateChanges) {
        String lastService = null;
        CommittableWritableStates lastWritableStates = null;

        final int n = stateChanges.stateChanges().size();

        for (int i = 0; i < n; i++) {
            final var stateChange = stateChanges.stateChanges().get(i);

            final var stateName = BlockStreamUtils.stateNameOf(stateChange.stateId());
            final var delimIndex = stateName.indexOf('.');
            if (delimIndex == -1) {
                throw new RuntimeException("State name '" + stateName + "' is not in the correct format");
            }
            final var serviceName = stateName.substring(0, delimIndex);
            final var state = stateLifecycleManager.getMutableState();
            final var writableStates = state.getWritableStates(serviceName);
            switch (stateChange.changeOperation().kind()) {
                case UNSET -> throw new IllegalStateException("Change operation is not set");
                case STATE_ADD, STATE_REMOVE -> {
                    // No-op
                }
                case SINGLETON_UPDATE -> {
                    final var singletonState = writableStates.getSingleton(stateChange.stateId());
                    final var singleton = BlockStreamUtils.singletonPutFor(stateChange.singletonUpdateOrThrow());
                    singletonState.put(singleton);
                }
                case MAP_UPDATE -> {
                    final var mapState = writableStates.get(stateChange.stateId());
                    final var key = BlockStreamUtils.mapKeyFor(
                            stateChange.mapUpdateOrThrow().keyOrThrow());
                    final var value = BlockStreamUtils.mapValueFor(
                            stateChange.mapUpdateOrThrow().valueOrThrow());
                    mapState.put(key, value);
                }
                case MAP_DELETE -> {
                    final var mapState = writableStates.get(stateChange.stateId());
                    mapState.remove(BlockStreamUtils.mapKeyFor(
                            stateChange.mapDeleteOrThrow().keyOrThrow()));
                }
                case QUEUE_PUSH -> {
                    final var queueState = writableStates.getQueue(stateChange.stateId());
                    queueState.add(BlockStreamUtils.queuePushFor(stateChange.queuePushOrThrow()));
                }
                case QUEUE_POP -> {
                    final var queueState = writableStates.getQueue(stateChange.stateId());
                    queueState.poll();
                }
            }
            if ((lastService != null && !lastService.equals(serviceName))) {
                lastWritableStates.commit();
            }
            if (i == n - 1) {
                ((CommittableWritableStates) writableStates).commit();
            }
            lastService = serviceName;
            lastWritableStates = (CommittableWritableStates) writableStates;
        }
    }
}
