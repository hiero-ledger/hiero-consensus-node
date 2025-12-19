// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_FILES;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.stateNameOf;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.NetworkStakingRewards;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.swirlds.base.time.Time;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Assertions;

/**
 * This validator verifies the following:
 * <ul>
 * 		<li>the final hash of the state after processing a series of blocks</li>
 * 		<li>the state entity counts match the number of created/modified entities</li>
 * 		<li>the calculated root block hashes of each block</li>
 * 		<li>the indirect state proofs preceding the final signed block proof</li>
 * </ul>
 */
public class StateAndBlockHashesValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(StateAndBlockHashesValidator.class);

    private static final Bytes FINAL_EXPECTED_STATE_HASH = Bytes.fromHex(
            "aa6f556fb309bec4daa93ca61938590e9c9481bf4a8d13e1f919ee3571818f0a89d102cde7a493b1726eebcdbe4e1390");
    private static final Bytes FINAL_EXPECTED_BLOCK_HASH = Bytes.fromHex(
            "a5d66e37d8a8a0f091eb982311f754260f6a6c7156a9f2a342010cf4f8333f041450ab02cc98de0829917b68ecfd4794");

    private final Bytes expectedStateRootHash;
    private final Bytes expectedFinalBlockHash;
    private final StateChangesSummary stateChangesSummary = new StateChangesSummary(new TreeMap<>());
    private final Map<String, Set<Object>> entityChanges = new LinkedHashMap<>();
    private final IndirectProofSequenceValidator indirectProofSeq = new IndirectProofSequenceValidator();

    private MerkleNodeState state;

    /**
     * Run the validator as a standalone program
     * @param ignored ignored params
     */
    public static void main(String[] ignored) {
        final long shard = 11;
        final long realm = 12;
        final var validator =
                new StateAndBlockHashesValidator(FINAL_EXPECTED_STATE_HASH, FINAL_EXPECTED_BLOCK_HASH, shard, realm);
        final var blocks = loadBlocks();
        validator.validateBlocks(blocks);
        logger.info("All blocks validated successfully");
    }

    public StateAndBlockHashesValidator(
            @NonNull final Bytes expectedStateRootHash,
            @NonNull final Bytes expectedFinalBlockHash,
            final long shard,
            final long realm) {
        this(expectedStateRootHash, expectedFinalBlockHash, shard, realm, false);
    }

    public StateAndBlockHashesValidator(
            @NonNull final Bytes expectedStateRootHash,
            @NonNull final Bytes expectedFinalBlockHash,
            final long shard,
            final long realm,
            final boolean initStatesApi) {
        this.expectedStateRootHash = requireNonNull(expectedStateRootHash);
        this.expectedFinalBlockHash = requireNonNull(expectedFinalBlockHash);

        System.setProperty("hedera.shard", String.valueOf(shard));
        System.setProperty("hedera.realm", String.valueOf(realm));
        System.setProperty("networkAdmin.upgradeSysFilesLoc", "data/config");

        final var metrics = new NoOpMetrics();
        final var platformConfig = ServicesMain.buildPlatformConfig();

        final var hedera = ServicesMain.newHedera(platformConfig, metrics, Time.getCurrent());
        this.state = hedera.newStateRoot();

        if (initStatesApi) {
            // Legacy initialization
            hedera.initializeStatesApi(state, GENESIS, platformConfig);
        } else {
            // Use internal VirtualMapState APIs to register service states directly
            registerFileServiceStates((VirtualMapState) state);
            registerTokenServiceStates((VirtualMapState) state);
            registerEntityIdServiceStates((VirtualMapState) state);
            logger.info("Registered internal states");
        }

        this.state = state.copy();

        logger.info("Validator ready to verify");
    }

    /**
     * Register FileService states using internal VirtualMapState APIs.
     * This bypasses the migration framework for minimal initialization.
     */
    private void registerFileServiceStates(@NonNull final VirtualMapState state) {
        final String serviceName = "FileService";

        // FILES map state (stateId = 6)
        final var filesDefinition = StateDefinition.onDisk(
                6, // FILES_STATE_ID
                "FILES",
                FileID.PROTOBUF,
                File.PROTOBUF,
                50_000 // MAX_FILES_HINT
                );
        final var filesMetadata = new StateMetadata<>(serviceName, filesDefinition);
        state.initializeState(filesMetadata);

        logger.info("Registered FileService.FILES state (ID 6)");
    }

    private void registerTokenServiceStates(@NonNull final VirtualMapState state) {
        final String serviceName = "TokenService";

        // ACCOUNTS map state (stateId = 2)
        final var accountsDefinition =
                StateDefinition.onDisk(2, "ACCOUNTS", AccountID.PROTOBUF, Account.PROTOBUF, 1_000_000);
        state.initializeState(new StateMetadata<>(serviceName, accountsDefinition));

        // TOKENS map state (stateId = 7)
        final var tokensDefinition = StateDefinition.onDisk(7, "TOKENS", TokenID.PROTOBUF, Token.PROTOBUF, 1_000_000);
        state.initializeState(new StateMetadata<>(serviceName, tokensDefinition));

        // NFTS map state (stateId = 4)
        final var nftsDefinition = StateDefinition.onDisk(4, "NFTS", NftID.PROTOBUF, Nft.PROTOBUF, 1_000_000);
        state.initializeState(new StateMetadata<>(serviceName, nftsDefinition));

        // TOKEN_RELS map state (stateId = 5)
        final var tokenRelsDefinition =
                StateDefinition.onDisk(5, "TOKEN_RELS", EntityIDPair.PROTOBUF, TokenRelation.PROTOBUF, 1_000_000);
        state.initializeState(new StateMetadata<>(serviceName, tokenRelsDefinition));

        // ALIASES map state (stateId = 3)
        final var aliasesDefinition =
                StateDefinition.onDisk(3, "ALIASES", ProtoBytes.PROTOBUF, AccountID.PROTOBUF, 1_000_000);
        state.initializeState(new StateMetadata<>(serviceName, aliasesDefinition));

        // STAKING_INFOS map state (stateId = 15)
        final var stakingInfosDefinition =
                StateDefinition.onDisk(15, "STAKING_INFOS", EntityNumber.PROTOBUF, StakingNodeInfo.PROTOBUF, 1_000);
        state.initializeState(new StateMetadata<>(serviceName, stakingInfosDefinition));

        // STAKING_NETWORK_REWARDS singleton (stateId = 24)
        final var stakingRewardsDefinition =
                StateDefinition.singleton(24, "STAKING_NETWORK_REWARDS", NetworkStakingRewards.PROTOBUF);
        state.initializeState(new StateMetadata<>(serviceName, stakingRewardsDefinition));

        // NODE_PAYMENTS singleton (stateId = 25)
        final var nodePaymentsDefinition = StateDefinition.singleton(25, "NODE_PAYMENTS", NodePayments.PROTOBUF);
        state.initializeState(new StateMetadata<>(serviceName, nodePaymentsDefinition));

        // Initialize singleton values with defaults
        final var networkRewards = NetworkStakingRewards.newBuilder()
                .pendingRewards(0)
                .totalStakedRewardStart(0)
                .totalStakedStart(0)
                .stakingRewardsActivated(true)
                .build();
        state.getWritableStates(serviceName).getSingleton(24).put(networkRewards);

        final var nodePayments = NodePayments.DEFAULT;
        state.getWritableStates(serviceName).getSingleton(25).put(nodePayments);

        logger.info(
                "Registered TokenService states (ACCOUNTS, TOKENS, NFTS, TOKEN_RELS, ALIASES, STAKING_INFOS, STAKING_NETWORK_REWARDS, NODE_PAYMENTS)");
    }

    private void registerEntityIdServiceStates(@NonNull final VirtualMapState state) {
        final String serviceName = "EntityIdService";

        // ENTITY_COUNTS singleton (stateId = 41)
        final var entityCountsDefinition = StateDefinition.singleton(41, "ENTITY_COUNTS", EntityCounts.PROTOBUF);
        state.initializeState(new StateMetadata<>(serviceName, entityCountsDefinition));

        // Initialize with empty/default value
        final var entityCounts = EntityCounts.DEFAULT;
        state.getWritableStates(serviceName).getSingleton(41).put(entityCounts);

        logger.info("Registered EntityIdService states (ENTITY_COUNTS)");
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info(
                "Beginning validation of expected state root hash {}, expected final block hash {}",
                expectedStateRootHash,
                expectedFinalBlockHash);
        var previousBlockHash = BlockStreamManager.ZERO_BLOCK_HASH;
        var startOfStateHash = requireNonNull(BlockStreamManager.ZERO_BLOCK_HASH);

        final int n = blocks.size();
        final int lastVerifiableIndex =
                blocks.reversed().stream().filter(b -> b.items().getLast().hasBlockProof()).findFirst().stream()
                        .mapToInt(b ->
                                (int) b.items().getFirst().blockHeaderOrThrow().number())
                        .findFirst()
                        .orElseThrow();
        final var digest = sha384DigestOrThrow();
        final IncrementalStreamingHasher incrementalBlockHashes = new IncrementalStreamingHasher(digest, List.of(), 0);
        printSubrootState("All Prev Block Hashes - Initial", incrementalBlockHashes);
        logger.info("Initialized subroot all previous block hashes");
        printSubrootState("All Prev Block Hashes", incrementalBlockHashes);
        for (int i = 0; i < n; i++) {
            final var block = blocks.get(i);
            if (i != 0) {
                final var stateToBeCopied = state;
                this.state = stateToBeCopied.copy();
                startOfStateHash = stateToBeCopied.getHash().getBytes();
                logger.info("New start of state hash for block {}: {}", i, startOfStateHash);
            }
            final IncrementalStreamingHasher inputTreeHasher =
                    new IncrementalStreamingHasher(digest, new ArrayList<>(), 0);
            printSubrootState("Input Tree Hasher - Start of Block " + i, inputTreeHasher);
            final IncrementalStreamingHasher outputTreeHasher =
                    new IncrementalStreamingHasher(digest, new ArrayList<>(), 0);
            printSubrootState("Output Tree Hasher - Start of Block " + i, outputTreeHasher);
            final IncrementalStreamingHasher consensusHeaderHasher =
                    new IncrementalStreamingHasher(digest, new ArrayList<>(), 0);
            printSubrootState("Consensus Header Hasher - Start of Block " + i, consensusHeaderHasher);
            final IncrementalStreamingHasher stateChangesHasher =
                    new IncrementalStreamingHasher(digest, new ArrayList<>(), 0);
            printSubrootState("State Changes Hasher - Start of Block " + i, stateChangesHasher);
            final IncrementalStreamingHasher traceDataHasher =
                    new IncrementalStreamingHasher(digest, new ArrayList<>(), 0);
            printSubrootState("Trace Data Hasher - Start of Block " + i, traceDataHasher);

            long firstBlockRound = -1;
            Timestamp firstConsensusTimestamp = null;
            for (final var item : block.items()) {
                if (firstConsensusTimestamp == null && item.hasBlockHeader()) {
                    firstConsensusTimestamp = item.blockHeaderOrThrow().blockTimestamp();
                    assertTrue(
                            firstConsensusTimestamp != null
                                    && !Objects.equals(firstConsensusTimestamp, Timestamp.DEFAULT),
                            "Block header timestamp is unset");
                }
                if (firstBlockRound == -1 && item.hasRoundHeader()) {
                    firstBlockRound = item.roundHeaderOrThrow().roundNumber();
                }
                hashSubTrees(
                        item,
                        inputTreeHasher,
                        outputTreeHasher,
                        consensusHeaderHasher,
                        stateChangesHasher,
                        traceDataHasher);
                if (item.hasStateChanges()) {
                    applyStateChanges(item.stateChangesOrThrow());
                }
            }
            assertNotNull(firstConsensusTimestamp, "No parseable timestamp found for block #" + i);

            if (i <= lastVerifiableIndex) {
                System.out.println("--------------------------------");
                logger.info("Calculating final hash for block {}", i);

                final var footer = block.items().get(block.items().size() - 2);
                assertTrue(footer.hasBlockFooter());
                final var lastBlockItem = block.items().getLast();
                assertTrue(lastBlockItem.hasBlockProof());
                final var blockProof = lastBlockItem.blockProofOrThrow();
                assertEquals(
                        previousBlockHash,
                        footer.blockFooterOrThrow().previousBlockRootHash(),
                        "Previous block hash mismatch for block " + blockProof.block());

                // The state changes hasher already incorporated the last state change, so compute its root hash
                final var finalStateChangesHash = Bytes.wrap(stateChangesHasher.computeRootHash());

                final var expectedRootAndSiblings = computeBlockHash(
                        firstConsensusTimestamp,
                        previousBlockHash,
                        incrementalBlockHashes,
                        startOfStateHash,
                        inputTreeHasher,
                        outputTreeHasher,
                        consensusHeaderHasher,
                        finalStateChangesHash,
                        traceDataHasher);
                final var expectedBlockHash = expectedRootAndSiblings.blockRootHash();
                validateBlockProof(
                        i,
                        firstBlockRound,
                        footer.blockFooterOrThrow(),
                        blockProof,
                        expectedBlockHash,
                        startOfStateHash,
                        previousBlockHash,
                        firstConsensusTimestamp,
                        expectedRootAndSiblings.siblingHashes());
                previousBlockHash = expectedBlockHash;

                incrementalBlockHashes.addLeaf(previousBlockHash.toByteArray());
                printSubrootState("All Prev Block Hashes", incrementalBlockHashes);
            }
        }
        logger.info("Summary of changes by service:\n{}", stateChangesSummary);

        // Update EntityCounts to reflect the actual entities created/modified in the blocks
        final var entityCountsState =
                state.getWritableStates(EntityIdService.NAME).<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID);
        updateEntityCounts(entityCountsState);

        assertEntityCountsMatch(entityCountsState);

        // To make sure that VirtualMapMetadata is persisted after all changes from the block stream were applied
        state.copy();
        state.getRoot().getHash();
        final var finalStateRootHash = requireNonNull(state.getHash()).getBytes();

        // Only validate final root hash if a non-zero expected value was provided
        if (!expectedStateRootHash.equals(finalStateRootHash)) {
            Assertions.fail(
                    "Final root hash mismatch - expected " + expectedStateRootHash + ", was " + finalStateRootHash);
        } else {
            logger.info("Verified final state root hash: {}", finalStateRootHash);
        }

        if (!expectedFinalBlockHash.equals(previousBlockHash)) {
            Assertions.fail(
                    "Final block hash mismatch - expected " + expectedFinalBlockHash + ", was " + previousBlockHash);
        } else {
            logger.info("Verified final block hash: {}", previousBlockHash);
        }

        indirectProofSeq.verify();
    }

    private void updateEntityCounts(final WritableSingletonState<EntityCounts> entityCountsState) {
        // Build updated counts based on tracked entity changes
        final var updatedCounts = new EntityCounts.Builder()
                .numAirdrops(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_PENDING_AIRDROPS.protoOrdinal()), Set.of())
                        .size())
                .numStakingInfos(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_STAKING_INFOS.protoOrdinal()), Set.of())
                        .size())
                .numContractStorageSlots(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_STORAGE.protoOrdinal()), Set.of())
                        .size())
                .numTokenRelations(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_TOKEN_RELS.protoOrdinal()), Set.of())
                        .size())
                .numAccounts(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal()), Set.of())
                        .size())
                .numAliases(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_ALIASES.protoOrdinal()), Set.of())
                        .size())
                .numContractBytecodes(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_BYTECODE.protoOrdinal()), Set.of())
                        .size())
                .numFiles(entityChanges
                        .getOrDefault(stateNameOf(STATE_ID_FILES.protoOrdinal()), Set.of())
                        .size())
                .numNfts(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_NFTS.protoOrdinal()), Set.of())
                        .size())
                .numNodes(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_NODES.protoOrdinal()), Set.of())
                        .size())
                .numSchedules(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_SCHEDULES_BY_ID.protoOrdinal()), Set.of())
                        .size())
                .numTokens(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_TOKENS.protoOrdinal()), Set.of())
                        .size())
                .numTopics(entityChanges
                        .getOrDefault(stateNameOf(StateIdentifier.STATE_ID_TOPICS.protoOrdinal()), Set.of())
                        .size())
                .build();

        entityCountsState.put(updatedCounts);
    }

    private void assertEntityCountsMatch(final WritableSingletonState<EntityCounts> entityCounts) {
        final var actualCounts = requireNonNull(entityCounts.get());
        final var expectedNumAirdrops = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_PENDING_AIRDROPS.protoOrdinal()), Set.of());
        final var expectedNumStakingInfos = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_STAKING_INFOS.protoOrdinal()), Set.of());
        final var expectedNumContractStorageSlots =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_STORAGE.protoOrdinal()), Set.of());
        final var expectedNumTokenRelations =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_TOKEN_RELS.protoOrdinal()), Set.of());
        final var expectedNumAccounts =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal()), Set.of());
        final var expectedNumAliases =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_ALIASES.protoOrdinal()), Set.of());
        final var expectedNumContractBytecodes =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_BYTECODE.protoOrdinal()), Set.of());
        final var expectedNumFiles = entityChanges.getOrDefault(stateNameOf(STATE_ID_FILES.protoOrdinal()), Set.of());
        final var expectedNumNfts =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_NFTS.protoOrdinal()), Set.of());
        final var expectedNumNodes =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_NODES.protoOrdinal()), Set.of());
        final var expectedNumSchedules = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_SCHEDULES_BY_ID.protoOrdinal()), Set.of());
        final var expectedNumTokens =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_TOKENS.protoOrdinal()), Set.of());
        final var expectedNumTopics =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_TOPICS.protoOrdinal()), Set.of());

        assertEquals(expectedNumAirdrops.size(), actualCounts.numAirdrops(), "Airdrop counts mismatch");
        assertEquals(expectedNumTokens.size(), actualCounts.numTokens(), "Token counts mismatch");
        assertEquals(
                expectedNumTokenRelations.size(), actualCounts.numTokenRelations(), "Token relation counts mismatch");
        assertEquals(expectedNumAccounts.size(), actualCounts.numAccounts(), "Account counts mismatch");
        assertEquals(expectedNumAliases.size(), actualCounts.numAliases(), "Alias counts mismatch");
        assertEquals(expectedNumStakingInfos.size(), actualCounts.numStakingInfos(), "Staking info counts mismatch");
        assertEquals(expectedNumNfts.size(), actualCounts.numNfts(), "Nft counts mismatch");

        assertEquals(
                expectedNumContractStorageSlots.size(),
                actualCounts.numContractStorageSlots(),
                "Contract storage slot counts mismatch");
        assertEquals(
                expectedNumContractBytecodes.size(),
                actualCounts.numContractBytecodes(),
                "Contract bytecode counts mismatch");

        assertEquals(expectedNumFiles.size(), actualCounts.numFiles(), "File counts mismatch");
        assertEquals(expectedNumNodes.size(), actualCounts.numNodes(), "Node counts mismatch");
        assertEquals(expectedNumSchedules.size(), actualCounts.numSchedules(), "Schedule counts mismatch");
        assertEquals(expectedNumTopics.size(), actualCounts.numTopics(), "Topic counts mismatch");
    }

    private void printSubrootState(String name, IncrementalStreamingHasher hasher) {
        System.out.println("------------------------------");
        System.out.println("Updated Subroot '" + name + "': ");
        System.out.println("Intermediate hash state: " + hasher.intermediateHashingState());
        System.out.println("Leaf count: " + hasher.leafCount());
    }

    private void hashSubTrees(
            final BlockItem item,
            final IncrementalStreamingHasher inputTreeHasher,
            final IncrementalStreamingHasher outputTreeHasher,
            final IncrementalStreamingHasher consensusHeaderHasher,
            final IncrementalStreamingHasher stateChangesHasher,
            final IncrementalStreamingHasher traceDataHasher) {
        final var itemSerialized = BlockItem.PROTOBUF.toBytes(item);

        switch (item.item().kind()) {
            case EVENT_HEADER, ROUND_HEADER -> {
                consensusHeaderHasher.addLeaf(itemSerialized.toByteArray());
                printSubrootState("consensus headers", consensusHeaderHasher);
            }
            case SIGNED_TRANSACTION -> {
                inputTreeHasher.addLeaf(itemSerialized.toByteArray());
                printSubrootState("input tree", inputTreeHasher);
            }
            case TRANSACTION_RESULT, TRANSACTION_OUTPUT, BLOCK_HEADER -> {
                outputTreeHasher.addLeaf(itemSerialized.toByteArray());
                printSubrootState("output tree", outputTreeHasher);
            }
            case STATE_CHANGES -> {
                stateChangesHasher.addLeaf(itemSerialized.toByteArray());
                printSubrootState("state changes", stateChangesHasher);
            }
            case TRACE_DATA -> {
                traceDataHasher.addLeaf(itemSerialized.toByteArray());
                printSubrootState("trace data", traceDataHasher);
            }
            default -> {
                // Other items are not part of the input/output trees
            }
        }
    }

    private static Bytes hashLeaf(final Bytes leafData) {
        final var digest = sha384DigestOrThrow();
        digest.update(StreamingTreeHasher.LEAF_PREFIX);
        digest.update(leafData.toByteArray());
        return Bytes.wrap(digest.digest());
    }

    private static Bytes hashInternalNodeSingleChild(final Bytes hash) {
        final var digest = sha384DigestOrThrow();
        digest.update(StreamingTreeHasher.SINGLE_CHILD_INTERNAL_NODE_PREFIX);
        digest.update(hash.toByteArray());
        return Bytes.wrap(digest.digest());
    }

    private static Bytes hashInternalNode(final Bytes leftChildHash, final Bytes rightChildHash) {
        final var digest = sha384DigestOrThrow();
        digest.update(StreamingTreeHasher.INTERNAL_NODE_PREFIX);
        digest.update(leftChildHash.toByteArray());
        digest.update(rightChildHash.toByteArray());
        return Bytes.wrap(digest.digest());
    }

    private record RootAndSiblingHashes(Bytes blockRootHash, MerkleSiblingHash[] siblingHashes) {}

    private RootAndSiblingHashes computeBlockHash(
            final Timestamp blockTimestamp,
            final Bytes previousBlockHash,
            final IncrementalStreamingHasher prevBlockRootsHasher,
            final Bytes startOfBlockStateHash,
            final IncrementalStreamingHasher inputTreeHasher,
            final IncrementalStreamingHasher outputTreeHasher,
            final IncrementalStreamingHasher consensusHeaderHasher,
            final Bytes finalStateChangesHash,
            final IncrementalStreamingHasher traceDataHasher) {
        final var prevBlocksRootHash = Bytes.wrap(prevBlockRootsHasher.computeRootHash());
        final var consensusHeaderHash = Bytes.wrap(consensusHeaderHasher.computeRootHash());
        final var inputTreeHash = Bytes.wrap(inputTreeHasher.computeRootHash());
        final var outputTreeHash = Bytes.wrap(outputTreeHasher.computeRootHash());
        final var traceDataHash = Bytes.wrap(traceDataHasher.computeRootHash());

        // Compute depth five hashes
        final var depth5Node1 = hashInternalNode(previousBlockHash, prevBlocksRootHash);
        final var depth5Node2 = hashInternalNode(startOfBlockStateHash, consensusHeaderHash);
        final var depth5Node3 = hashInternalNode(inputTreeHash, outputTreeHash);
        final var depth5Node4 = hashInternalNode(finalStateChangesHash, traceDataHash);

        // Compute depth four hashes
        final var depth4Node1 = hashInternalNode(depth5Node1, depth5Node2);
        final var depth4Node2 = hashInternalNode(depth5Node3, depth5Node4);

        // Compute depth three hash (no 'node 2' at this level since reserved subroots 9-16 aren't encoded in the tree)
        final var depth3Node1 = hashInternalNode(depth4Node1, depth4Node2);

        // Compute depth two hashes (timestamp + last right sibling)
        final var timestamp = Timestamp.PROTOBUF.toBytes(blockTimestamp);
        final var depth2Node1 = hashLeaf(timestamp);
        final var depth2Node2 = hashInternalNodeSingleChild(depth3Node1);

        // Compute the block's root hash (depth 1)
        final var root = hashInternalNode(depth2Node1, depth2Node2);

        System.out.println("Depth 6 (Inputs):");
        System.out.println("depth6node1: " + previousBlockHash);
        System.out.println("depth6node2: " + prevBlocksRootHash);
        System.out.println("depth6node3: " + startOfBlockStateHash);
        System.out.println("depth6node4: " + consensusHeaderHash);
        System.out.println("depth6node5: " + inputTreeHash);
        System.out.println("depth6node6: " + outputTreeHash);
        System.out.println("depth6node7: " + finalStateChangesHash);
        System.out.println("depth6node8: " + traceDataHash);

        System.out.println("Depth 5:");
        System.out.println("depth5Node1: " + depth5Node1);
        System.out.println("depth5Node2: " + depth5Node2);
        System.out.println("depth5Node3: " + depth5Node3);
        System.out.println("depth5Node4: " + depth5Node4);

        System.out.println("Depth 4:");
        System.out.println("depth4Node1: " + depth4Node1);
        System.out.println("depth4Node2: " + depth4Node2);

        System.out.println("Depth 3:");
        System.out.println("depth3Node1: " + depth3Node1);

        System.out.println("Depth 2:");
        System.out.println("Block ts: " + blockTimestamp);
        System.out.println("tsBytes: " + timestamp);
        System.out.println("depth2Node1 (hashed ts): " + depth2Node1);
        System.out.println("depth2Node2: " + depth2Node2);

        System.out.println("Root Hash: " + root);

        // Siblings for a state proof
        System.out.println("Sibling Hashes for State Proof:");
        System.out.println("Level 6 Sibling (right child): " + prevBlocksRootHash);
        System.out.println("Level 5 Sibling (right child): " + depth5Node2);
        System.out.println("Level 4 Sibling (right child): " + depth4Node2);

        return new RootAndSiblingHashes(root.replicate(), new MerkleSiblingHash[] {
            new MerkleSiblingHash(false, prevBlocksRootHash.replicate()),
            new MerkleSiblingHash(false, depth5Node2.replicate()),
            new MerkleSiblingHash(false, depth4Node2.replicate()),
        });
    }

    private boolean indirectProofsNeedVerification() {
        return indirectProofSeq.containsIndirectProofs();
    }

    private void validateBlockProof(
            final long blockNumber,
            final long firstRound,
            @NonNull final BlockFooter footer,
            @NonNull final BlockProof proof,
            @NonNull final Bytes expectedBlockHash,
            @NonNull final Bytes startOfStateHash,
            @NonNull final Bytes previousBlockHash,
            @NonNull final Timestamp blockTimestamp,
            @NonNull final MerkleSiblingHash[] expectedSiblingHashes) {
        assertEquals(blockNumber, proof.block());
        // todo enable when we know what the starting state (genesis) hash should be
        //        assertEquals(
        //                footer.startOfBlockStateRootHash(),
        //                startOfStateHash,
        //                "Wrong start of state hash for block #" + blockNumber);

        // Our proof method will be different depending on whether this is a direct or indirect proof.
        // Direct proofs have a signed block proof; indirect proofs do not.
        if (!proof.hasSignedBlockProof()) {
            // This is an indirect proof, so a block state proof must be present
            assertTrue(
                    proof.hasBlockStateProof(),
                    "Indirect proof for block #%s is missing a block state proof".formatted(blockNumber));

            // We can't verify the indirect proof until we have a signed block proof, so store the indirect proof
            // for later verification and short-circuit the remainder of the proof verification
            indirectProofSeq.registerProof(
                    blockNumber, proof, expectedBlockHash, previousBlockHash, blockTimestamp, expectedSiblingHashes);
        } else if (indirectProofsNeedVerification()) {
            indirectProofSeq.registerProof(
                    blockNumber, proof, expectedBlockHash, previousBlockHash, blockTimestamp, expectedSiblingHashes);
        }
    }

    private void applyStateChanges(@NonNull final StateChanges stateChanges) {
        String lastService = null;
        CommittableWritableStates lastWritableStates = null;

        final int n = stateChanges.stateChanges().size();

        for (int i = 0; i < n; i++) {
            final var stateChange = stateChanges.stateChanges().get(i);

            final var stateName = stateNameOf(stateChange.stateId());
            final var delimIndex = stateName.indexOf('.');
            if (delimIndex == -1) {
                Assertions.fail("State name '" + stateName + "' is not in the correct format");
            }
            final var serviceName = stateName.substring(0, delimIndex);
            final var writableStates = state.getWritableStates(serviceName);
            final int stateId = stateChange.stateId();
            switch (stateChange.changeOperation().kind()) {
                case UNSET -> throw new IllegalStateException("Change operation is not set");
                case STATE_ADD -> {
                    // State registration from block stream
                    // In the current implementation, states are auto-created on first access,
                    // so we just log that we saw the STATE_ADD operation
                    final var stateAddInfo = stateChange.stateAddOrThrow();
                    logger.info(
                            "STATE_ADD operation for state ID {} ({}), type: {}",
                            stateId,
                            stateName,
                            stateAddInfo.stateType());
                }
                case STATE_REMOVE -> {
                    // No-op for now - states aren't actually removed during validation
                }
                case SINGLETON_UPDATE -> {
                    final var singletonState = writableStates.getSingleton(stateId);
                    final var singleton = BlockStreamUtils.singletonPutFor(stateChange.singletonUpdateOrThrow());
                    singletonState.put(singleton);
                    stateChangesSummary.countSingletonPut(serviceName, stateId);
                }
                case MAP_UPDATE -> {
                    final var mapState = writableStates.get(stateId);
                    final var key = BlockStreamUtils.mapKeyFor(
                            stateChange.mapUpdateOrThrow().keyOrThrow());
                    final var value = BlockStreamUtils.mapValueFor(
                            stateChange.mapUpdateOrThrow().valueOrThrow());
                    mapState.put(key, value);
                    entityChanges
                            .computeIfAbsent(stateName, k -> new HashSet<>())
                            .add(key);
                    stateChangesSummary.countMapUpdate(serviceName, stateId);
                }
                case MAP_DELETE -> {
                    final var mapState = writableStates.get(stateId);
                    mapState.remove(BlockStreamUtils.mapKeyFor(
                            stateChange.mapDeleteOrThrow().keyOrThrow()));
                    final var keyToRemove = BlockStreamUtils.mapKeyFor(
                            stateChange.mapDeleteOrThrow().keyOrThrow());
                    final var maybeTrackedKeys = entityChanges.get(stateName);
                    if (maybeTrackedKeys != null) {
                        maybeTrackedKeys.remove(keyToRemove);
                    }
                    stateChangesSummary.countMapDelete(serviceName, stateId);
                }
                case QUEUE_PUSH -> {
                    final var queueState = writableStates.getQueue(stateId);
                    queueState.add(BlockStreamUtils.queuePushFor(stateChange.queuePushOrThrow()));
                    stateChangesSummary.countQueuePush(serviceName, stateId);
                }
                case QUEUE_POP -> {
                    final var queueState = writableStates.getQueue(stateId);
                    queueState.poll();
                    stateChangesSummary.countQueuePop(serviceName, stateId);
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

    private record ServiceChangesSummary(
            Map<Integer, Long> singletonPuts,
            Map<Integer, Long> mapUpdates,
            Map<Integer, Long> mapDeletes,
            Map<Integer, Long> queuePushes,
            Map<Integer, Long> queuePops) {

        private static final String PREFIX = "    * ";

        public static ServiceChangesSummary newSummary(@NonNull final String serviceName) {
            return new ServiceChangesSummary(
                    new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        }

        @Override
        public String toString() {
            final var sb = new StringBuilder();
            singletonPuts.forEach((stateKey, count) -> sb.append(PREFIX)
                    .append(stateKey)
                    .append(" singleton put ")
                    .append(count)
                    .append(" times")
                    .append('\n'));
            mapUpdates.forEach((stateKey, count) -> sb.append(PREFIX)
                    .append(stateKey)
                    .append(" map updated ")
                    .append(count)
                    .append(" times, deleted ")
                    .append(mapDeletes.getOrDefault(stateKey, 0L))
                    .append(" times")
                    .append('\n'));
            queuePushes.forEach((stateKey, count) -> sb.append(PREFIX)
                    .append(stateKey)
                    .append(" queue pushed ")
                    .append(count)
                    .append(" times, popped ")
                    .append(queuePops.getOrDefault(stateKey, 0L))
                    .append(" times")
                    .append('\n'));
            return sb.toString();
        }
    }

    private record StateChangesSummary(Map<String, ServiceChangesSummary> serviceChanges) {
        @Override
        public String toString() {
            final var sb = new StringBuilder();
            serviceChanges.forEach((serviceName, summary) -> {
                sb.append("- ").append(serviceName).append(" -\n").append(summary);
            });
            return sb.toString();
        }

        public void countSingletonPut(String serviceName, int stateId) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .singletonPuts()
                    .merge(stateId, 1L, Long::sum);
        }

        public void countMapUpdate(String serviceName, int stateId) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .mapUpdates()
                    .merge(stateId, 1L, Long::sum);
        }

        public void countMapDelete(String serviceName, int stateId) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .mapDeletes()
                    .merge(stateId, 1L, Long::sum);
        }

        public void countQueuePush(String serviceName, int stateId) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .queuePushes()
                    .merge(stateId, 1L, Long::sum);
        }

        public void countQueuePop(String serviceName, int stateId) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .queuePops()
                    .merge(stateId, 1L, Long::sum);
        }
    }

    private static List<Block> loadBlocks() {
        try {
            final Path dir = Path.of("hedera-node/test-clients/src/main/resources/block-merkle-tree");

            try (Stream<Path> files = Files.list(dir)) {
                return files.filter(p -> p.getFileName().toString().endsWith(".blk.json"))
                        .sorted(Comparator.comparing(Path::toString))
                        .map(p -> {
                            try {
                                return Block.JSON.parse(Bytes.wrap(Files.readAllBytes(p)));
                            } catch (IOException | ParseException e) {
                                throw new IllegalStateException("Unable to parse pending proof bytes from " + p, e);
                            }
                        })
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load block files", e);
        }
    }
}
