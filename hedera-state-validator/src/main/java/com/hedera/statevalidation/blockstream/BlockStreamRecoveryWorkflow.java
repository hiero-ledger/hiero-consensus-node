// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.ApplyBlocksCommand.DEFAULT_TARGET_ROUND;
import static com.hedera.statevalidation.util.PlatformContextHelper.getPlatformContext;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.ProtoConstants;
import com.hedera.pbj.runtime.ProtoParserTools;
import com.hedera.pbj.runtime.io.ReadableSequentialData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
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
import org.hiero.base.crypto.CryptoUtils;
import org.hiero.consensus.concurrent.throttle.RateLimiter;
import org.hiero.consensus.model.node.NodeId;
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
                    }
                }

                if (item.hasStateChanges()) {
                    BinaryStateChangeApplier.applyStateChanges(
                            state, StateChanges.PROTOBUF.toBytes(item.stateChangesOrThrow()));
                }
            }
        });

        if (targetRound != DEFAULT_TARGET_ROUND && currentRound.get() != targetRound) {
            throw new RuntimeException("Block stream is incomplete."
                    + " Expected target round is %d, last applied round is %d"
                            .formatted(targetRound, currentRound.get()));
        }

        // To make sure that VirtualMapMetadata is persisted after all changes from the block stream were applied
        stateLifecycleManager.copyMutableState();
        state.getHash();
        final var rootHash = requireNonNull(state.getHash()).getBytes();

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
                    platformContext,
                    selfId,
                    outputPath,
                    signedState.reserve("BlockStreamWorkflow.applyBlocks()"),
                    stateLifecycleManager);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (!expectedRootHash.isEmpty() && !expectedRootHash.equals(rootHash.toString())) {
            throw new RuntimeException("Excepted and actual hashes do not match. \n Expected: %s \n Actual: %s "
                    .formatted(expectedRootHash, rootHash));
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
    /**
     * Parses binary protobuf {@link StateChanges} and applies mutations through the {@link BinaryState} API.
     *
     * <p>The parser reads the protobuf wire format to extract state change operations
     * (singleton updates, map updates/deletes, queue pushes/pops) and delegates them to the
     * corresponding {@link BinaryState} methods, which handle key composition, value wrapping,
     * and queue state management internally.
     */
    private static final class BinaryStateChangeApplier {
        private static void applyStateChanges(
                @NonNull final BinaryState binaryState, @NonNull final Bytes stateChangesBytes) {
            final ReadableSequentialData input = stateChangesBytes.toReadableSequentialData();
            while (input.hasRemaining()) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // consensus_timestamp: field 1, message => (1 << 3) | 2 = 10
                    case 10 -> skipMessage(input);
                    // state_changes:       field 2, message => (2 << 3) | 2 = 18
                    case 18 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final long endPosition = input.position() + messageLength;
                            processStateChange(binaryState, input, endPosition);
                        }
                    }
                    default -> skipField(input, tag);
                }
            }
        }

        private static void processStateChange(
                @NonNull final BinaryState binaryState,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            int stateId = -1;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // state_id: field 1, uint32 varint => (1 << 3) | 0 = 8
                    case 8 -> stateId = ProtoParserTools.readUint32(input);
                    // state_add: field 2, message => (2 << 3) | 2 = 18
                    // state_remove: field 3, message => (3 << 3) | 2 = 26
                    case 18, 26 -> skipMessage(input);
                    // singleton_update: field 4, message => (4 << 3) | 2 = 34
                    case 34 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final Bytes rawValue =
                                    readOneOfPayload(input, input.position() + messageLength, "SingletonUpdateChange");
                            binaryState.updateSingleton(requireStateId(stateId), rawValue);
                        }
                    }
                    // map_update: field 5, message => (5 << 3) | 2 = 42
                    case 42 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            processMapUpdate(
                                    binaryState, requireStateId(stateId), input, input.position() + messageLength);
                        }
                    }
                    // map_delete: field 6, message => (6 << 3) | 2 = 50
                    case 50 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            processMapDelete(
                                    binaryState, requireStateId(stateId), input, input.position() + messageLength);
                        }
                    }
                    // queue_push: field 7, message => (7 << 3) | 2 = 58
                    case 58 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            final Bytes rawElement =
                                    readOneOfPayload(input, input.position() + messageLength, "QueuePushChange");
                            binaryState.pushQueue(requireStateId(stateId), rawElement);
                        }
                    }
                    // queue_pop: field 8, message => (8 << 3) | 2 = 66
                    case 66 -> {
                        skipMessage(input);
                        binaryState.popQueue(requireStateId(stateId));
                    }
                    default -> skipField(input, tag);
                }
            }
        }

        private static void processMapUpdate(
                @NonNull final BinaryState binaryState,
                final int stateId,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            Bytes rawKey = null;
            Bytes rawValue = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                switch (tag) {
                    // key: field 1, message => (1 << 3) | 2 = 10K
                    case 10 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            rawKey = readMapKeyPayload(stateId, input, input.position() + messageLength);
                        }
                    }
                    // value: field 2, message => (2 << 3) | 2 = 18
                    case 18 -> {
                        final int messageLength = input.readVarInt(false);
                        if (messageLength > 0) {
                            rawValue = readOneOfPayload(input, input.position() + messageLength, "MapChangeValue");
                        }
                    }
                    default -> skipField(input, tag);
                }
            }
            if (rawKey == null || rawValue == null) {
                throw new IllegalStateException("MapChangeKey or MapChangeValue missing");
            }
            binaryState.updateKv(stateId, rawKey, rawValue);
        }

        private static void processMapDelete(
                @NonNull final BinaryState binaryState,
                final int stateId,
                @NonNull final ReadableSequentialData input,
                final long endPosition) {
            Bytes rawKey = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                // key: field 1, message => (1 << 3) | 2 = 10
                if (tag == 10) {
                    final int messageLength = input.readVarInt(false);
                    if (messageLength > 0) {
                        rawKey = readMapKeyPayload(stateId, input, input.position() + messageLength);
                    }
                } else {
                    skipField(input, tag);
                }
            }
            if (rawKey == null) {
                throw new IllegalStateException("MapChangeKey missing in MapDeleteChange");
            }
            binaryState.removeKv(stateId, rawKey);
        }

        /**
         * Reads the first delimited (length-prefixed) field payload within a protobuf message.
         * Used for extracting raw key/value bytes from oneOf wrappers and map key messages.
         */
        private static Bytes readOneOfPayload(
                @NonNull final ReadableSequentialData input,
                final long endPosition,
                @NonNull final String description) {
            Bytes payload = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                final var wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
                if (payload == null && wireType == ProtoConstants.WIRE_TYPE_DELIMITED) {
                    final int length = input.readVarInt(false);
                    payload = input.readBytes(length);
                } else {
                    skipField(input, wireType);
                }
            }
            if (payload == null) {
                throw new IllegalStateException(description + " payload missing");
            }
            return payload;
        }

        /**
         * Reads the first delimited field payload from a map key message.
         * Most block-stream key payloads are byte-compatible with the state key bytes stored in the
         * VirtualMap. Token relationship keys are the important exception: block stream uses
         * TokenAssociation while state stores EntityIDPair, whose field ordering is different.
         */
        private static Bytes readMapKeyPayload(
                final int stateId, @NonNull final ReadableSequentialData input, final long endPosition) {
            Bytes payload = null;
            Integer fieldNumber = null;
            while (input.position() < endPosition) {
                final int tag = input.readVarInt(false);
                final var wireType = ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK);
                if (payload == null && wireType == ProtoConstants.WIRE_TYPE_DELIMITED) {
                    fieldNumber = tag >>> ProtoParserTools.TAG_FIELD_OFFSET;
                    final int length = input.readVarInt(false);
                    payload = input.readBytes(length);
                } else {
                    skipField(input, wireType);
                }
            }
            if (payload == null) {
                throw new IllegalStateException("MapChangeKey payload missing");
            }
            return normalizeMapKeyPayload(stateId, fieldNumber, payload);
        }

        /**
         * Normalizes map key payload bytes to match the format stored in the VirtualMap.
         * Most block-stream keys are byte-compatible, but token relationship keys are an
         * exception: the block stream encodes them as {@link TokenAssociation} (field 1 = token_id,
         * field 2 = account_id), while the state stores {@link EntityIDPair} (field 1 = account_id,
         * field 2 = token_id).
         *
         * @param stateId the numeric state identifier; 9 = token relationships
         * @param fieldNumber the protobuf field number from the MapChangeKey oneOf wrapper;
         *                    field 2 indicates a TokenAssociation encoding that needs conversion
         * @param payload the raw key bytes extracted from the block stream
         * @return normalized key bytes compatible with the VirtualMap key format
         */
        private static Bytes normalizeMapKeyPayload(
                final int stateId, final int fieldNumber, @NonNull final Bytes payload) {
            if (stateId == 9 && fieldNumber == 2) {
                try {
                    final var tokenAssociation = TokenAssociation.PROTOBUF.parse(payload);
                    return EntityIDPair.PROTOBUF.toBytes(
                            new EntityIDPair(tokenAssociation.accountId(), tokenAssociation.tokenId()));
                } catch (ParseException e) {
                    throw new IllegalStateException("Failed to normalize token relationship key", e);
                }
            }
            return payload;
        }

        private static int requireStateId(final int stateId) {
            if (stateId < 0) {
                throw new IllegalStateException("StateChange missing state_id");
            }
            return stateId;
        }

        /**
         * Skips a protobuf field based on the wire type encoded in the given tag.
         *
         * @param input the input stream positioned at the field's value
         * @param tag the raw protobuf tag (field number + wire type)
         */
        private static void skipField(@NonNull final ReadableSequentialData input, final int tag) {
            skipField(input, ProtoConstants.get(tag & ProtoConstants.TAG_WIRE_TYPE_MASK));
        }

        /**
         * Skips a protobuf field value for the given wire type.
         *
         * @param input the input stream positioned at the field's value
         * @param wireType the protobuf wire type indicating how to skip the value
         */
        private static void skipField(
                @NonNull final ReadableSequentialData input, @NonNull final ProtoConstants wireType) {
            try {
                ProtoParserTools.skipField(input, wireType);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to skip protobuf field with wire type " + wireType, e);
            }
        }

        private static void skipMessage(@NonNull final ReadableSequentialData input) {
            final int messageLength = input.readVarInt(false);
            input.skip(messageLength);
        }
    }
}
