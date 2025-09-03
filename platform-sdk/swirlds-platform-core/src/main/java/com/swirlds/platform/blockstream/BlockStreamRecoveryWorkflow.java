// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.blockstream;

import static com.swirlds.platform.blockstream.BlockStreamUtils.mapKeyFor;
import static com.swirlds.platform.blockstream.BlockStreamUtils.mapValueFor;
import static com.swirlds.platform.blockstream.BlockStreamUtils.queuePushFor;
import static com.swirlds.platform.blockstream.BlockStreamUtils.singletonPutFor;
import static com.swirlds.platform.blockstream.BlockStreamUtils.stateNameOf;
import static com.swirlds.platform.cli.BlockStreamApplyCommand.DEFAULT_TARGET_ROUND;
import static com.swirlds.platform.state.service.PlatformStateFacade.DEFAULT_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.util.PlatformStateInitializer.initAndProcessState;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.recovery.internal.RecoveryPlatform;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileWriter;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.spi.CommittableWritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * This workflow applies a set of blocks to a given state and creates a new snapshot once the state
 * is advanced to the target round
 */
public class BlockStreamRecoveryWorkflow {

    private final Set<String> servicesWritten = new HashSet<>();

    private final MerkleNodeState state;
    private final long targetRound;
    private final Path outputPath;
    private final String expectedRootHash;
    private static final long SHARD = 0;
    private static final long REALM = 0;

    public BlockStreamRecoveryWorkflow(
            MerkleNodeState state, long targetRound, Path outputPath, String expectedRootHash) {
        this.state = state;
        this.targetRound = targetRound;
        this.outputPath = outputPath;
        this.expectedRootHash = expectedRootHash;
    }

    public static void applyBlocks(
            Path pathToInitState,
            List<Path> configurationPaths,
            Path blockStreamDirectory,
            String mainClassName,
            NodeId selfId,
            long targetRound,
            Path outputPath,
            String expectedHash)
            throws IOException {
        initAndProcessState(
                pathToInitState, configurationPaths, mainClassName, selfId, (reservedState, app, platformContext) -> {
                    final Configuration configuration = platformContext.getConfiguration();

                    MerkleNodeState initialState = reservedState.get().getState();
                    initialState.throwIfImmutable("initial state must be mutable");
                    final RecoveryPlatform platform =
                            new RecoveryPlatform(configuration, reservedState.get(), selfId, false);
                    ConsensusStateEventHandler consensusStateEventHandler = app.newConsensusStateEvenHandler();
                    SemanticVersion softwareVersion =
                            DEFAULT_PLATFORM_STATE_FACADE.creationSoftwareVersionOf(initialState);
                    reservedState.get().init(platformContext);
                    consensusStateEventHandler.onStateInitialized(
                            initialState, platform, InitTrigger.RECOVERY, softwareVersion);
                    app.init(platform, platform.getSelfId());
                    final var blocks = BlockStreamUtils.readBlocks(blockStreamDirectory, false);
                    final BlockStreamRecoveryWorkflow workflow = new BlockStreamRecoveryWorkflow(
                            reservedState.get().getState(), targetRound, outputPath, expectedHash);
                    workflow.applyBlocks(blocks, selfId, platformContext);
                });
    }

    public void applyBlocks(@NonNull final List<Block> blocks, NodeId selfId, PlatformContext platformContext) {
        boolean foundStartingRound = false;
        final long initRound = DEFAULT_PLATFORM_STATE_FACADE.roundOf(state);
        final long firstRoundToApply = initRound + 1;
        long currentRound = initRound;
        outer:
        for (final Block block : blocks) {
            for (final BlockItem item : block.items()) {
                // if the first block item belongs to the round after the first round to apply, we can't proceed
                // as the block stream is incomplete
                if (!foundStartingRound
                        && item.hasRoundHeader()
                        && item.roundHeader().roundNumber() > firstRoundToApply) {
                    throw new RuntimeException(
                            ("Given blockstream doesn't have a proper starting round. Must have a block item with a round = %d. "
                                            + "The oldest round found is %d")
                                    .formatted(
                                            firstRoundToApply,
                                            item.roundHeader().roundNumber()));
                }

                foundStartingRound |=
                        item.hasRoundHeader() && item.roundHeader().roundNumber() == firstRoundToApply;

                // skip forward to the starting round
                if (!foundStartingRound) {
                    continue;
                }

                // do not go beyond the target round
                if (item.hasRoundHeader()) {
                    long itemRound = item.roundHeader().roundNumber();
                    if (itemRound > targetRound) {
                        break outer;
                    } else {
                        if (itemRound != currentRound + 1) {
                            throw new RuntimeException("Unexpected round number. Expected = %d, actual = %d"
                                    .formatted(currentRound + 1, itemRound));
                        }
                        currentRound++;
                    }
                }

                servicesWritten.clear();
                if (item.hasStateChanges()) {
                    applyStateChanges(item.stateChangesOrThrow());
                }
                servicesWritten.forEach(name -> ((CommittableWritableStates) state.getWritableStates(name)).commit());
            }
        }

        if (targetRound != DEFAULT_TARGET_ROUND && currentRound != targetRound) {
            throw new RuntimeException(
                    "Block stream is incomplete. Expected target round is %d, last applied round is %d"
                            .formatted(targetRound, currentRound));
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

            final var stateName = stateNameOf(stateChange.stateId(), SHARD, REALM);
            final var delimIndex = stateName.indexOf('.');
            if (delimIndex == -1) {
                throw new RuntimeException("State name '" + stateName + "' is not in the correct format");
            }
            final var serviceName = stateName.substring(0, delimIndex);
            final var writableStates = state.getWritableStates(serviceName);
            servicesWritten.add(serviceName);
            final var stateKey = stateName.substring(delimIndex + 1);
            switch (stateChange.changeOperation().kind()) {
                case UNSET -> throw new IllegalStateException("Change operation is not set");
                case STATE_ADD, STATE_REMOVE -> {
                    // No-op
                }
                case SINGLETON_UPDATE -> {
                    final var singletonState = writableStates.getSingleton(stateKey);
                    final var singleton = singletonPutFor(stateChange.singletonUpdateOrThrow());
                    singletonState.put(singleton);
                }
                case MAP_UPDATE -> {
                    final var mapState = writableStates.get(stateKey);
                    final var key = mapKeyFor(stateChange.mapUpdateOrThrow().keyOrThrow());
                    final var value = mapValueFor(stateChange.mapUpdateOrThrow().valueOrThrow());
                    mapState.put(key, value);
                }
                case MAP_DELETE -> {
                    final var mapState = writableStates.get(stateKey);
                    mapState.remove(mapKeyFor(stateChange.mapDeleteOrThrow().keyOrThrow()));
                }
                case QUEUE_PUSH -> {
                    final var queueState = writableStates.getQueue(stateKey);
                    queueState.add(queuePushFor(stateChange.queuePushOrThrow()));
                }
                case QUEUE_POP -> {
                    final var queueState = writableStates.getQueue(stateKey);
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
