// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.blockstream;

import static com.hedera.statevalidation.ApplyBlocksCommand.DEFAULT_TARGET_ROUND;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.statevalidation.util.PlatformContextHelper;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;

/**
 * This workflow applies a set of blocks to a given state and creates a new snapshot once the state
 * is advanced to the target round
 */
public class BlockStreamRecoveryWorkflow {

    private final MerkleNodeState state;
    private final long targetRound;
    private final Path outputPath;
    private final String expectedRootHash;

    public BlockStreamRecoveryWorkflow(
            @NonNull final MerkleNodeState state,
            long targetRound,
            @NonNull final Path outputPath,
            @NonNull final String expectedRootHash) {
        this.state = state;
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
        final DeserializedSignedState deserializedSignedState;
        try {
            deserializedSignedState = StateUtils.getDeserializedSignedState();
        } catch (ConstructableRegistryException e) {
            throw new RuntimeException(e);
        }
        final MerkleNodeState state =
                deserializedSignedState.reservedSignedState().get().getState();
        final var blocks = BlockStreamAccess.readBlocks(blockStreamDirectory, false);
        final BlockStreamRecoveryWorkflow workflow =
                new BlockStreamRecoveryWorkflow(state, targetRound, outputPath, expectedHash);
        workflow.applyBlocks(blocks, selfId, PlatformContextHelper.getPlatformContext());
    }

    public void applyBlocks(
            @NonNull final Stream<Block> blocks,
            @NonNull final NodeId selfId,
            @NonNull final PlatformContext platformContext) {
        AtomicBoolean foundStartingRound = new AtomicBoolean();
        final long initRound = DEFAULT_PLATFORM_STATE_FACADE.roundOf(state);
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
        state.copy();
        state.getRoot().getHash();
        final var rootHash = requireNonNull(state.getHash()).getBytes();

        if (!expectedRootHash.isEmpty() && !expectedRootHash.equals(rootHash.toString())) {
            throw new RuntimeException("Excepted and actual hashes do not match. \n Expected: %s \n Actual: %s "
                    .formatted(expectedRootHash, rootHash));
        }

        final SignedState signedState = new SignedState(
                platformContext.getConfiguration(),
                CryptoStatic::verifySignature,
                state,
                "BlockStreamWorkflow.applyBlocks()",
                false,
                false,
                false,
                DEFAULT_PLATFORM_STATE_FACADE);

        try {
            SignedStateFileWriter.writeSignedStateFilesToDirectory(
                    platformContext, selfId, outputPath, signedState, DEFAULT_PLATFORM_STATE_FACADE);
        } catch (IOException e) {
            throw new RuntimeException(e);
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
