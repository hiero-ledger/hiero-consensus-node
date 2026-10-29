// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.ApplyBlocksCommand.DEFAULT_TARGET_ROUND;
import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.node.app.hapi.utils.blocks.BinaryStateChangeParser;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.statevalidation.util.ProgressReporter;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.platform.context.PlatformContext;
import com.swirlds.state.BinaryState;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.throttle.RateLimiter;
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.SignedStateFileWriter;
import org.hiero.consensus.state.signed.SignedState;

/**
 * This workflow applies a set of blocks to a given state and creates a new snapshot once the state
 * is advanced to the target round.
 *
 * <p>State changes from the block stream are applied through the {@link BinaryState} API,
 * which operates directly on raw protobuf bytes without deserializing into domain objects.
 * This avoids the overhead of the codec-based State API (WritableStates, WritableKVState, etc.)
 * where each state change would require deserialization to a Java domain object, buffering in
 * writable state adapters, and re-serialization on commit.
 */
public class BlockStreamRecoveryWorkflow {

    private static final Logger log = LogManager.getLogger(BlockStreamRecoveryWorkflow.class);

    /** Sleep interval used in the rate-limiter spin loop. */
    private static final long RATE_LIMITER_SLEEP_NANOS = 1_000_000L; // 1 ms

    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private final long targetRound;
    private final Path outputPath;
    private final String expectedRootHash;
    private final int roundsPerSecond;

    public BlockStreamRecoveryWorkflow(
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedRootHash) {
        this(stateLifecycleManager, targetRound, outputPath, expectedRootHash, Integer.MAX_VALUE);
    }

    /**
     * Creates a new workflow with optional rate limiting.
     *
     * @param stateLifecycleManager the state lifecycle manager
     * @param targetRound           the last round to apply (or {@code DEFAULT_TARGET_ROUND} for all)
     * @param outputPath            the directory where the resulting snapshot is written
     * @param expectedRootHash      expected hash of the resulting state (empty to skip verification)
     * @param roundsPerSecond       maximum rounds to apply per second (≥ 1). Controls CPU/IO load
     *                              independently of state size. {@code Integer.MAX_VALUE} effectively
     *                              disables rate limiting.
     */
    public BlockStreamRecoveryWorkflow(
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedRootHash,
            int roundsPerSecond) {
        this.stateLifecycleManager = stateLifecycleManager;
        this.targetRound = targetRound;
        this.outputPath = outputPath;
        this.expectedRootHash = expectedRootHash;
        this.roundsPerSecond = roundsPerSecond;
    }

    public static void applyBlocks(
            @NonNull final Path blockStreamDirectory,
            @NonNull final NodeId selfId,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedHash)
            throws IOException {
        applyBlocks(blockStreamDirectory, selfId, targetRound, outputPath, expectedHash, Integer.MAX_VALUE);
    }

    /**
     * Reads blocks from the given directory and applies them to the default state with optional
     * rate limiting.
     *
     * @param blockStreamDirectory the directory containing block stream files
     * @param selfId               the node ID
     * @param targetRound          the last round to apply
     * @param outputPath           the output directory for the resulting snapshot
     * @param expectedHash         expected hash of the resulting state
     * @param roundsPerSecond      maximum rounds per second ({@code Integer.MAX_VALUE} = unlimited).
     *                             See {@link RateLimiter} for semantics.
     */
    public static void applyBlocks(
            @NonNull final Path blockStreamDirectory,
            @NonNull final NodeId selfId,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedHash,
            int roundsPerSecond)
            throws IOException {

        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        getPlatformContext().getMetrics(),
                        getPlatformContext().getTime(),
                        getPlatformContext().getConfiguration(),
                        getPlatformContext().getFileSystemManager());

        stateLifecycleManager.initWithState(StateUtils.getDefaultState());
        validateNoMissingBlocks(blockStreamDirectory);
        final var blocks = BlockStreamAccess.readBlocks(blockStreamDirectory, false);
        final BlockStreamRecoveryWorkflow workflow = new BlockStreamRecoveryWorkflow(
                stateLifecycleManager, targetRound, outputPath, expectedHash, roundsPerSecond);
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

        // At Integer.MAX_VALUE the interval is effectively 0, so skip the limiter entirely
        // to avoid unnecessary overhead on every round.
        final RateLimiter rateLimiter = roundsPerSecond < Integer.MAX_VALUE
                ? new RateLimiter(platformContext.getTime(), roundsPerSecond)
                : null;

        // Progress reporting: percentage-based when targetRound is known, count-based otherwise
        final boolean bounded = targetRound != DEFAULT_TARGET_ROUND;
        final ProgressReporter progress = bounded
                ? new ProgressReporter("Block stream recovery", targetRound - initRound)
                : new ProgressReporter("Block stream recovery", 1); // unbounded fallback

        blocks.forEach(block -> {
            for (final BlockItem item : block.items()) {
                // if the first block item belongs to the round after the first round to apply, we can't proceed
                // as the block stream is incomplete
                if (!foundStartingRound.get()
                        && item.hasRoundHeader()
                        && item.roundHeader().roundNumber() > firstRoundToApply) {
                    throw new RuntimeException(("Given blockstream doesn't have a proper starting round."
                                    + " Must have a block item with a round = %d. "
                                    + "The oldest round found is %d")
                            .formatted(firstRoundToApply, item.roundHeader().roundNumber()));
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
                        // Arriving at a new round header means the previous round's state changes
                        // are fully applied. Throttle here to cap the rate of applied rounds.
                        // RateLimiter initializes lastOperation to Instant.EPOCH, so the very first
                        // requestAndTrigger() always succeeds .
                        rateLimit(rateLimiter);
                        currentRound.incrementAndGet();
                        if (bounded) {
                            progress.advance(1);
                        } else {
                            progress.advanceUnbounded(1);
                        }
                    }
                }

                if (item.hasStateChanges()) {
                    BinaryStateChangeParser.applyStateChanges(
                            state, StateChanges.PROTOBUF.toBytes(item.stateChangesOrThrow()));
                }
            }
        });

        if (targetRound != DEFAULT_TARGET_ROUND && currentRound.get() != targetRound) {
            throw new RuntimeException("Block stream is incomplete."
                    + " Expected target round is %d, last applied round is %d"
                            .formatted(targetRound, currentRound.get()));
        }

        // To make sure that VirtualMap.Metadata is persisted after all changes from the block stream were applied
        stateLifecycleManager.copyMutableState();
        state.getHash();
        final var rootHash = requireNonNull(state.getHash()).getBytes();

        if (!expectedRootHash.isEmpty() && !expectedRootHash.equals(rootHash.toString())) {
            throw new RuntimeException("Expected and actual hashes do not match. \n Expected: %s \n Actual: %s "
                    .formatted(expectedRootHash, rootHash));
        }

        final SignedState signedState = new SignedState(
                platformContext.getConfiguration(),
                CryptoUtils::verifySignature,
                state,
                "BlockStreamWorkflow.applyBlocks()",
                false,
                false,
                false);

        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                new VirtualMapStateLifecycleManager(
                        platformContext.getMetrics(),
                        platformContext.getTime(),
                        platformContext.getConfiguration(),
                        platformContext.getFileSystemManager());
        try {
            SignedStateFileWriter.writeSignedStateFilesToDirectory(
                    platformContext.getConfiguration(),
                    platformContext.getFileSystemManager(),
                    selfId,
                    outputPath,
                    signedState.reserve("BlockStreamWorkflow.applyBlocks()"),
                    stateLifecycleManager);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Validates that block files in the given directory form a contiguous sequence with no gaps.
     * This provides an early, descriptive failure when block files are missing from the directory,
     * rather than deferring to a cryptic hash mismatch or an unclear round-number error later in
     * the workflow.
     *
     * @param blockStreamDirectory the directory containing block stream files
     * @throws IOException if an I/O error occurs while listing files
     * @throws RuntimeException if missing block files are detected
     */
    private static void validateNoMissingBlocks(@NonNull final Path blockStreamDirectory) throws IOException {
        final List<Long> blockNumbers;
        try (var stream = Files.walk(blockStreamDirectory)) {
            blockNumbers = stream.filter(p -> BlockStreamAccess.isBlockFile(p, false))
                    .map(BlockStreamAccess::extractBlockNumber)
                    .filter(n -> n != -1)
                    .sorted()
                    .toList();
        }

        if (blockNumbers.size() < 2) {
            return;
        }

        final List<Long> missingBlocks = new ArrayList<>();
        for (int i = 1; i < blockNumbers.size(); i++) {
            final long prev = blockNumbers.get(i - 1);
            final long curr = blockNumbers.get(i);
            for (long missing = prev + 1; missing < curr; missing++) {
                missingBlocks.add(missing);
            }
        }

        if (!missingBlocks.isEmpty()) {
            throw new RuntimeException(("Block stream directory is missing %d block file(s). "
                            + "First present block = %d, last present block = %d. Missing blocks: %s")
                    .formatted(
                            missingBlocks.size(),
                            blockNumbers.getFirst(),
                            blockNumbers.getLast(),
                            missingBlocks.size() <= 20
                                    ? missingBlocks.toString()
                                    : missingBlocks.subList(0, 20) + " ... (" + missingBlocks.size() + " total)"));
        }

        log.info(
                "Block file contiguity validated: {} block files present, range [{}, {}]",
                blockNumbers.size(),
                blockNumbers.getFirst(),
                blockNumbers.getLast());
    }

    /**
     * Blocks until the rate limiter allows the next round to proceed.
     * Follows the same spin-sleep pattern used in {@code TeacherPullVirtualTreeReceiveTask}.
     */
    private static void rateLimit(@Nullable final RateLimiter rateLimiter) {
        if (rateLimiter != null) {
            while (!rateLimiter.requestAndTrigger()) {
                LockSupport.parkNanos(RATE_LIMITER_SLEEP_NANOS);
            }
        }
    }
}
