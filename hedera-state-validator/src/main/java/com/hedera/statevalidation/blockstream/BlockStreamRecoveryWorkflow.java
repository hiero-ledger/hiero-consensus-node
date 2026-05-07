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
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.hiero.base.crypto.CryptoUtils;
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

    private final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private final long targetRound;
    private final Path outputPath;
    private final String expectedRootHash;

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

                if (item.hasStateChanges()) {
                    BinaryStateChangeApplier.applyStateChanges(
                            state, StateChanges.PROTOBUF.toBytes(item.stateChangesOrThrow()));
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
                CryptoUtils::verifySignature,
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
