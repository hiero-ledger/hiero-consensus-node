// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACTIVE_HINTS_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACTIVE_PROOF_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_FILES;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.combine;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SWIRLDS_LOG;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.STATE_METADATA_FILE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.junit.support.validators.block.BlockStreamUtils.stateNameOf;
import static com.hedera.services.bdd.junit.support.validators.block.ChildHashUtils.hashesByName;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.QueuePushChange;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TokenAssociation;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.hapi.node.state.primitives.ProtoString;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.blocks.impl.NaiveStreamingTreeHasher;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.crypto.CryptoStatic;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableSingletonState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.crypto.Hash;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that asserts the state changes in the block stream, when applied directly to a {@link MerkleNodeState}
 * initialized with the genesis {@link Service} schemas, result in the given root hash.
 */
public class StateChangesValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(StateChangesValidator.class);
    private static final SplittableRandom RANDOM = new SplittableRandom(System.currentTimeMillis());
    private static final MerkleCryptography CRYPTO = TestMerkleCryptoFactory.getInstance();

    private static final int HASH_SIZE = 48;
    private static final int VISUALIZATION_HASH_DEPTH = 5;
    /**
     * The probability that the validator will verify an intermediate block proof; we always verify the first and last.
     */
    private static final double PROOF_VERIFICATION_PROB = 0.05;

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final Pattern CHILD_STATE_PATTERN = Pattern.compile("\\s+\\d+ \\w+\\s+(\\S+)\\s+.+\\s+(.+)");

    private final Hash genesisStateHash;
    private final Path pathToNode0SwirldsLog;
    private final Bytes expectedRootHash;
    private final Set<String> servicesWritten = new HashSet<>();
    private final StateChangesSummary stateChangesSummary = new StateChangesSummary(new TreeMap<>());
    private final Map<String, Set<Object>> entityChanges = new LinkedHashMap<>();

    private Instant lastStateChangesTime;
    private StateChanges lastStateChanges;
    private MerkleNodeState state;

    @Nullable
    private final HintsLibrary hintsLibrary;

    @Nullable
    private final HistoryLibrary historyLibrary;

    public enum HintsEnabled {
        YES,
        NO
    }

    public enum HistoryEnabled {
        YES,
        NO
    }

    public static void main(String[] args) {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        final var validator = new StateChangesValidator(
                Bytes.fromHex(
                        "00e8337bc1e1c5730c1a7f2dde8c53ed22f8664cd18069b71be52161a34071d6913d619f8867520e611dca03a2f71d15"),
                node0Dir.resolve("output/swirlds.log"),
                node0Dir.resolve("data/config/application.properties"),
                node0Dir.resolve("data/config"),
                HintsEnabled.YES,
                HistoryEnabled.YES);
        final var blocks =
                BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(node0Dir.resolve("data/blockStreams/block-0.0.3"));
        validator.validateBlocks(blocks);
    }

    public static final Factory FACTORY = new Factory() {
        @NonNull
        @Override
        public BlockStreamValidator create(@NonNull final HapiSpec spec) {
            return newValidatorFor(spec);
        }

        @Override
        public boolean appliesTo(@NonNull HapiSpec spec) {
            // Embedded networks don't have saved states or a Merkle tree to validate hashes against
            return spec.targetNetworkOrThrow().type() == SUBPROCESS_NETWORK;
        }
    };

    /**
     * Constructs a validator that will assert the state changes in the block stream are consistent with the
     * root hash found in the latest saved state directory from a node targeted by the given spec.
     *
     * @param spec the spec
     * @return the validator
     */
    public static StateChangesValidator newValidatorFor(@NonNull final HapiSpec spec) {
        requireNonNull(spec);
        final var latestStateDir = findMaybeLatestSavedStateFor(spec);
        if (latestStateDir == null) {
            throw new AssertionError("No saved state directory found");
        }
        final var rootHash = findRootHashFrom(latestStateDir.resolve(STATE_METADATA_FILE));
        if (rootHash == null) {
            throw new AssertionError("No root hash found in state metadata file");
        }
        if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
            throw new IllegalArgumentException("Cannot validate state changes for an embedded network");
        }
        try {
            final var node0 = subProcessNetwork.getRequiredNode(byNodeId(0));
            final var genesisConfigTxt = node0.metadata().workingDirOrThrow().resolve("genesis-config.txt");
            Files.writeString(genesisConfigTxt, subProcessNetwork.genesisConfigTxt());
            final var isHintsEnabled = spec.startupProperties().getBoolean("tss.hintsEnabled");
            final var isHistoryEnabled = spec.startupProperties().getBoolean("tss.historyEnabled");
            return new StateChangesValidator(
                    rootHash,
                    node0.getExternalPath(SWIRLDS_LOG),
                    node0.getExternalPath(APPLICATION_PROPERTIES),
                    node0.getExternalPath(DATA_CONFIG_DIR),
                    isHintsEnabled ? HintsEnabled.YES : HintsEnabled.NO,
                    isHistoryEnabled ? HistoryEnabled.YES : HistoryEnabled.NO);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public StateChangesValidator(
            @NonNull final Bytes expectedRootHash,
            @NonNull final Path pathToNode0SwirldsLog,
            @NonNull final Path pathToOverrideProperties,
            @NonNull final Path pathToUpgradeSysFilesLoc,
            @NonNull final HintsEnabled hintsEnabled,
            @NonNull final HistoryEnabled historyEnabled) {
        this.expectedRootHash = requireNonNull(expectedRootHash);
        this.pathToNode0SwirldsLog = requireNonNull(pathToNode0SwirldsLog);

        System.setProperty(
                "hedera.app.properties.path",
                pathToOverrideProperties.toAbsolutePath().toString());
        System.setProperty(
                "networkAdmin.upgradeSysFilesLoc",
                pathToUpgradeSysFilesLoc.toAbsolutePath().toString());
        System.setProperty("tss.hintsEnabled", "" + (hintsEnabled == HintsEnabled.YES));
        System.setProperty("tss.historyEnabled", "" + (historyEnabled == HistoryEnabled.YES));
        unarchiveGenesisNetworkJson(pathToUpgradeSysFilesLoc);
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        final var versionConfig = bootstrapConfig.getConfigData(VersionConfig.class);
        final var servicesVersion = versionConfig.servicesVersion();
        final var metrics = new NoOpMetrics();
        final var hedera = ServicesMain.newHedera(metrics, new PlatformStateFacade());
        this.state = hedera.newStateRoot();
        final var platformConfig = ServicesMain.buildPlatformConfig();
        hedera.initializeStatesApi(state, GENESIS, platformConfig);
        final var stateToBeCopied = state;
        state = state.copy();
        this.hintsLibrary = (hintsEnabled == HintsEnabled.YES) ? new HintsLibraryImpl() : null;
        this.historyLibrary = (historyEnabled == HistoryEnabled.YES) ? new HistoryLibraryImpl() : null;
        // get the state hash before applying the state changes from current block
        this.genesisStateHash = CRYPTO.digestTreeSync(stateToBeCopied.getRoot());

        logger.info("Registered all Service and migrated state definitions to version {}", servicesVersion);
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Beginning validation of expected root hash {}", expectedRootHash);
        var previousBlockHash = BlockStreamManager.ZERO_BLOCK_HASH;
        var startOfStateHash = requireNonNull(genesisStateHash).getBytes();

        final int n = blocks.size();
        final var verificationKey = (hintsLibrary != null)
                ? blocks.stream()
                        .flatMap(b -> b.items().stream())
                        .filter(BlockItem::hasStateChanges)
                        .flatMap(b -> b.stateChangesOrThrow().stateChanges().stream())
                        .filter(change -> change.stateId() == STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal())
                        .map(change -> change.singletonUpdateOrThrow().hintsConstructionValueOrThrow())
                        .filter(HintsConstruction::hasHintsScheme)
                        .map(c ->
                                c.hintsSchemeOrThrow().preprocessedKeysOrThrow().verificationKey())
                        .findFirst()
                        .orElseThrow()
                : null;
        final int lastVerifiableIndex =
                blocks.reversed().stream().filter(b -> b.items().getLast().hasBlockProof()).findFirst().stream()
                        .mapToInt(b ->
                                (int) b.items().getFirst().blockHeaderOrThrow().number())
                        .findFirst()
                        .orElseThrow();
        for (int i = 0; i < n; i++) {
            final var block = blocks.get(i);
            final var shouldVerifyProof =
                    i == 0 || i == lastVerifiableIndex || RANDOM.nextDouble() < PROOF_VERIFICATION_PROB;
            if (i != 0 && shouldVerifyProof) {
                final var stateToBeCopied = state;
                this.state = stateToBeCopied.copy();
                startOfStateHash =
                        CRYPTO.digestTreeSync(stateToBeCopied.getRoot()).getBytes();
            }
            final StreamingTreeHasher inputTreeHasher = new NaiveStreamingTreeHasher();
            final StreamingTreeHasher outputTreeHasher = new NaiveStreamingTreeHasher();
            Timestamp expectedFirstUserTxnTime = null;
            boolean firstUserTxnSeen = false;
            for (final var item : block.items()) {
                if (item.hasBlockHeader()) {
                    if (i == 0) {
                        assertEquals(0, item.blockHeaderOrThrow().number(), "Genesis block number should be 0");
                    }
                    expectedFirstUserTxnTime = item.blockHeaderOrThrow().firstTransactionConsensusTime();
                } else if (item.hasTransactionResult() && !firstUserTxnSeen) {
                    final var result = item.transactionResultOrThrow();
                    assertEquals(expectedFirstUserTxnTime, result.consensusTimestampOrThrow());
                    firstUserTxnSeen = true;
                }
                servicesWritten.clear();
                if (shouldVerifyProof) {
                    hashInputOutputTree(item, inputTreeHasher, outputTreeHasher);
                }
                if (item.hasStateChanges()) {
                    final var changes = item.stateChangesOrThrow();
                    final var at = asInstant(changes.consensusTimestampOrThrow());
                    if (lastStateChanges != null && at.isBefore(requireNonNull(lastStateChangesTime))) {
                        Assertions.fail("State changes are not in chronological order - last changes were \n "
                                + lastStateChanges + "\ncurrent changes are \n  " + changes);
                    }
                    lastStateChanges = changes;
                    lastStateChangesTime = at;
                    applyStateChanges(item.stateChangesOrThrow());
                }
                servicesWritten.forEach(name -> ((CommittableWritableStates) state.getWritableStates(name)).commit());
            }
            if (!firstUserTxnSeen) {
                assertNull(expectedFirstUserTxnTime, "Block had no user transactions");
            }
            if (i <= lastVerifiableIndex) {
                final var lastBlockItem = block.items().getLast();
                assertTrue(lastBlockItem.hasBlockProof());
                final var blockProof = lastBlockItem.blockProofOrThrow();
                assertEquals(
                        previousBlockHash,
                        blockProof.previousBlockRootHash(),
                        "Previous block hash mismatch for block " + blockProof.block());

                if (shouldVerifyProof) {
                    final var expectedBlockHash =
                            computeBlockHash(startOfStateHash, previousBlockHash, inputTreeHasher, outputTreeHasher);
                    validateBlockProof(blockProof, expectedBlockHash, verificationKey);
                    previousBlockHash = expectedBlockHash;
                } else {
                    previousBlockHash = requireNonNull(
                                    blocks.get(i + 1).items().getLast().blockProof())
                            .previousBlockRootHash();
                }
            }
        }
        logger.info("Summary of changes by service:\n{}", stateChangesSummary);

        final var entityCounts =
                state.getWritableStates(EntityIdService.NAME).<EntityCounts>getSingleton(ENTITY_COUNTS_KEY);
        assertEntityCountsMatch(entityCounts);

        CRYPTO.digestTreeSync(state.getRoot());
        final var rootHash = requireNonNull(state.getHash()).getBytes();
        if (!expectedRootHash.equals(rootHash)) {
            final var expectedHashes = getMaybeLastHashMnemonics(pathToNode0SwirldsLog);
            if (expectedHashes == null) {
                throw new AssertionError("No expected hashes found in " + pathToNode0SwirldsLog);
            }
            final var actualHashes = hashesFor(state.getRoot());
            final var errorMsg = new StringBuilder("Hashes did not match for the following states,");
            expectedHashes.forEach((stateName, expectedHash) -> {
                final var actualHash = actualHashes.get(stateName);
                if (!expectedHash.equals(actualHash)) {
                    errorMsg.append("\n    * ")
                            .append(stateName)
                            .append(" - expected ")
                            .append(expectedHash)
                            .append(", was ")
                            .append(actualHash);
                }
            });
            Assertions.fail(errorMsg.toString());
        }
    }

    private void assertEntityCountsMatch(final WritableSingletonState<EntityCounts> entityCounts) {
        final var actualCounts = requireNonNull(entityCounts.get());
        final var expectedNumAirdrops = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_PENDING_AIRDROPS.protoOrdinal()), Set.of());
        final var expectedNumStakingInfos =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_STAKING_INFO.protoOrdinal()), Set.of());
        final var expectedNumContractStorageSlots = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_CONTRACT_STORAGE.protoOrdinal()), Set.of());
        final var expectedNumTokenRelations = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_TOKEN_RELATIONS.protoOrdinal()), Set.of());
        final var expectedNumAccounts =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_ACCOUNTS.protoOrdinal()), Set.of());
        final var expectedNumAliases =
                entityChanges.getOrDefault(stateNameOf(StateIdentifier.STATE_ID_ALIASES.protoOrdinal()), Set.of());
        final var expectedNumContractBytecodes = entityChanges.getOrDefault(
                stateNameOf(StateIdentifier.STATE_ID_CONTRACT_BYTECODE.protoOrdinal()), Set.of());
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

    private void hashInputOutputTree(
            final BlockItem item,
            final StreamingTreeHasher inputTreeHasher,
            final StreamingTreeHasher outputTreeHasher) {
        final var itemSerialized = BlockItem.PROTOBUF.toBytes(item);
        final var digest = sha384DigestOrThrow();
        switch (item.item().kind()) {
            case EVENT_HEADER, EVENT_TRANSACTION, ROUND_HEADER -> inputTreeHasher.addLeaf(
                    ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            case TRANSACTION_RESULT, TRANSACTION_OUTPUT, STATE_CHANGES, BLOCK_HEADER -> outputTreeHasher.addLeaf(
                    ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            default -> {
                // Other items are not part of the input/output trees
            }
        }
    }

    private Bytes computeBlockHash(
            final Bytes startOfBlockStateHash,
            final Bytes previousBlockHash,
            final StreamingTreeHasher inputTreeHasher,
            final StreamingTreeHasher outputTreeHasher) {
        final var inputTreeHash = inputTreeHasher.rootHash().join();
        final var outputTreeHash = outputTreeHasher.rootHash().join();

        final var leftHash = combine(previousBlockHash, inputTreeHash);
        final var rightHash = combine(outputTreeHash, startOfBlockStateHash);
        return combine(leftHash, rightHash);
    }

    private void validateBlockProof(
            @NonNull final BlockProof proof, @NonNull final Bytes blockHash, @Nullable final Bytes verificationKey) {
        var provenHash = blockHash;
        final var siblingHashes = proof.siblingHashes();
        if (!siblingHashes.isEmpty()) {
            for (final var siblingHash : siblingHashes) {
                // Our indirect proofs always provide right sibling hashes
                provenHash = combine(provenHash, siblingHash.siblingHash());
            }
        }
        if (verificationKey != null) {
            final var signature = proof.blockSignature();
            final var verified = hintsLibrary.verifyAggregate(signature, blockHash, verificationKey, 1, 3);
            assertTrue(verified, "Block proof signature verification failed for " + proof);
        } else {
            final var expectedSignature = Bytes.wrap(noThrowSha384HashOf(provenHash.toByteArray()));
            assertEquals(expectedSignature, proof.blockSignature(), "Signature mismatch for " + proof);
        }
    }

    private Map<String, String> hashesFor(@NonNull final MerkleNode state) {
        final var sb = new StringBuilder();
        new MerkleTreeVisualizer(state).setDepth(VISUALIZATION_HASH_DEPTH).render(sb);
        logger.info("Replayed hashes:\n{}", sb);
        return hashesByName(sb.toString());
    }

    private void applyStateChanges(@NonNull final StateChanges stateChanges) {
        for (final var stateChange : stateChanges.stateChanges()) {
            final var stateName = stateNameOf(stateChange.stateId());
            final var delimIndex = stateName.indexOf('.');
            if (delimIndex == -1) {
                Assertions.fail("State name '" + stateName + "' is not in the correct format");
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
                    stateChangesSummary.countSingletonPut(serviceName, stateKey);
                    if (historyLibrary != null
                            && stateChange.stateId() == STATE_ID_ACTIVE_PROOF_CONSTRUCTION.protoOrdinal()) {
                        final var construction = (HistoryProofConstruction) singleton;
                        if (construction.hasTargetProof()) {
                            logger.info("Verifying chain of trust for #{}", construction.constructionId());
                            assertTrue(
                                    historyLibrary.verifyChainOfTrust(
                                            construction.targetProofOrThrow().proof()),
                                    "Chain of trust verification failed for " + construction);
                        }
                    }
                }
                case MAP_UPDATE -> {
                    final var mapState = writableStates.get(stateKey);
                    mapState.put(
                            mapKeyFor(stateChange.mapUpdateOrThrow().keyOrThrow()),
                            mapValueFor(stateChange.mapUpdateOrThrow().valueOrThrow()));
                    entityChanges
                            .computeIfAbsent(stateName, k -> new HashSet<>())
                            .add(mapKeyFor(stateChange.mapUpdateOrThrow().keyOrThrow()));
                    stateChangesSummary.countMapUpdate(serviceName, stateKey);
                }
                case MAP_DELETE -> {
                    final var mapState = writableStates.get(stateKey);
                    mapState.remove(mapKeyFor(stateChange.mapDeleteOrThrow().keyOrThrow()));
                    final var keyToRemove =
                            mapKeyFor(stateChange.mapDeleteOrThrow().keyOrThrow());
                    entityChanges.get(stateName).remove(keyToRemove);
                    stateChangesSummary.countMapDelete(serviceName, stateKey);
                }
                case QUEUE_PUSH -> {
                    final var queueState = writableStates.getQueue(stateKey);
                    queueState.add(queuePushFor(stateChange.queuePushOrThrow()));
                    stateChangesSummary.countQueuePush(serviceName, stateKey);
                }
                case QUEUE_POP -> {
                    final var queueState = writableStates.getQueue(stateKey);
                    queueState.poll();
                    stateChangesSummary.countQueuePop(serviceName, stateKey);
                }
            }
        }
    }

    /**
     * If the given path does not contain the genesis network JSON, recovers it from the archive directory.
     *
     * @param path the path to the network directory
     * @throws IllegalStateException if the genesis network JSON cannot be found
     * @throws UncheckedIOException  if an I/O error occurs
     */
    private void unarchiveGenesisNetworkJson(@NonNull final Path path) {
        final var desiredPath = path.resolve(DiskStartupNetworks.GENESIS_NETWORK_JSON);
        if (!desiredPath.toFile().exists()) {
            final var archivedPath =
                    path.resolve(DiskStartupNetworks.ARCHIVE).resolve(DiskStartupNetworks.GENESIS_NETWORK_JSON);
            if (!archivedPath.toFile().exists()) {
                throw new IllegalStateException("No archived genesis network JSON found at " + archivedPath);
            }
            try {
                Files.move(archivedPath, desiredPath);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private record ServiceChangesSummary(
            Map<String, Long> singletonPuts,
            Map<String, Long> mapUpdates,
            Map<String, Long> mapDeletes,
            Map<String, Long> queuePushes,
            Map<String, Long> queuePops) {
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

        public void countSingletonPut(String serviceName, String stateKey) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .singletonPuts()
                    .merge(stateKey, 1L, Long::sum);
        }

        public void countMapUpdate(String serviceName, String stateKey) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .mapUpdates()
                    .merge(stateKey, 1L, Long::sum);
        }

        public void countMapDelete(String serviceName, String stateKey) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .mapDeletes()
                    .merge(stateKey, 1L, Long::sum);
        }

        public void countQueuePush(String serviceName, String stateKey) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .queuePushes()
                    .merge(stateKey, 1L, Long::sum);
        }

        public void countQueuePop(String serviceName, String stateKey) {
            serviceChanges
                    .computeIfAbsent(serviceName, ServiceChangesSummary::newSummary)
                    .queuePops()
                    .merge(stateKey, 1L, Long::sum);
        }
    }

    private static @Nullable Bytes findRootHashFrom(@NonNull final Path stateMetadataPath) {
        try (final var lines = Files.lines(stateMetadataPath)) {
            return lines.filter(line -> line.startsWith("HASH:"))
                    .map(line -> line.substring(line.length() - 2 * HASH_SIZE))
                    .map(Bytes::fromHex)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            logger.error("Failed to read state metadata file {}", stateMetadataPath, e);
            return null;
        }
    }

    private static @Nullable Path findMaybeLatestSavedStateFor(@NonNull final HapiSpec spec) {
        final var savedStateDirs = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(SAVED_STATES_DIR))
                .map(Path::toAbsolutePath)
                .toList();
        for (final var savedStatesDir : savedStateDirs) {
            try {
                final var latestRoundPath = findLargestNumberDirectory(savedStatesDir);
                if (latestRoundPath != null) {
                    return latestRoundPath;
                }
            } catch (IOException e) {
                logger.error("Failed to find the latest saved state directory in {}", savedStatesDir, e);
            }
        }
        return null;
    }

    private static @Nullable Path findLargestNumberDirectory(@NonNull final Path savedStatesDir) throws IOException {
        long latestRound = -1;
        Path latestRoundPath = null;
        try (final var stream = Files.newDirectoryStream(savedStatesDir, StateChangesValidator::isNumberDirectory)) {
            for (final var numberDirectory : stream) {
                final var round = Long.parseLong(numberDirectory.getFileName().toString());
                if (round > latestRound) {
                    latestRound = round;
                    latestRoundPath = numberDirectory;
                }
            }
        }
        return latestRoundPath;
    }

    private static boolean isNumberDirectory(@NonNull final Path path) {
        return path.toFile().isDirectory()
                && NUMBER_PATTERN.matcher(path.getFileName().toString()).matches();
    }

    private static @Nullable Map<String, String> getMaybeLastHashMnemonics(final Path path) {
        StringBuilder sb = null;
        boolean sawAllChildHashes = false;
        try {
            final var lines = Files.readAllLines(path);
            for (final var line : lines) {
                if (line.startsWith("(root)")) {
                    sb = new StringBuilder();
                    sawAllChildHashes = false;
                } else if (sb != null) {
                    final var childStateMatcher = CHILD_STATE_PATTERN.matcher(line);
                    sawAllChildHashes |= !childStateMatcher.matches();
                    if (!sawAllChildHashes) {
                        sb.append(line).append('\n');
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Could not read hashes from {}", path, e);
            return null;
        }
        logger.info("Read hashes:\n{}", sb);
        return sb == null ? null : hashesByName(sb.toString());
    }

    private static Object singletonPutFor(@NonNull final SingletonUpdateChange singletonUpdateChange) {
        return switch (singletonUpdateChange.newValue().kind()) {
            case UNSET -> throw new IllegalStateException("Singleton update value is not set");
            case BLOCK_INFO_VALUE -> singletonUpdateChange.blockInfoValueOrThrow();
            case CONGESTION_LEVEL_STARTS_VALUE -> singletonUpdateChange.congestionLevelStartsValueOrThrow();
            case ENTITY_NUMBER_VALUE -> new EntityNumber(singletonUpdateChange.entityNumberValueOrThrow());
            case EXCHANGE_RATE_SET_VALUE -> singletonUpdateChange.exchangeRateSetValueOrThrow();
            case NETWORK_STAKING_REWARDS_VALUE -> singletonUpdateChange.networkStakingRewardsValueOrThrow();
            case NODE_REWARDS_VALUE -> singletonUpdateChange.nodeRewardsValueOrThrow();
            case BYTES_VALUE -> new ProtoBytes(singletonUpdateChange.bytesValueOrThrow());
            case STRING_VALUE -> new ProtoString(singletonUpdateChange.stringValueOrThrow());
            case RUNNING_HASHES_VALUE -> singletonUpdateChange.runningHashesValueOrThrow();
            case THROTTLE_USAGE_SNAPSHOTS_VALUE -> singletonUpdateChange.throttleUsageSnapshotsValueOrThrow();
            case TIMESTAMP_VALUE -> singletonUpdateChange.timestampValueOrThrow();
            case BLOCK_STREAM_INFO_VALUE -> singletonUpdateChange.blockStreamInfoValueOrThrow();
            case PLATFORM_STATE_VALUE -> singletonUpdateChange.platformStateValueOrThrow();
            case ROSTER_STATE_VALUE -> singletonUpdateChange.rosterStateValueOrThrow();
            case HINTS_CONSTRUCTION_VALUE -> singletonUpdateChange.hintsConstructionValueOrThrow();
            case ENTITY_COUNTS_VALUE -> singletonUpdateChange.entityCountsValueOrThrow();
            case HISTORY_PROOF_CONSTRUCTION_VALUE -> singletonUpdateChange.historyProofConstructionValueOrThrow();
            case CRS_STATE_VALUE -> singletonUpdateChange.crsStateValueOrThrow();
        };
    }

    private static Object queuePushFor(@NonNull final QueuePushChange queuePushChange) {
        return switch (queuePushChange.value().kind()) {
            case UNSET, PROTO_STRING_ELEMENT -> throw new IllegalStateException("Queue push value is not supported");
            case PROTO_BYTES_ELEMENT -> new ProtoBytes(queuePushChange.protoBytesElementOrThrow());
            case TRANSACTION_RECEIPT_ENTRIES_ELEMENT -> queuePushChange.transactionReceiptEntriesElementOrThrow();
        };
    }

    private static Object mapKeyFor(@NonNull final MapChangeKey mapChangeKey) {
        return switch (mapChangeKey.keyChoice().kind()) {
            case UNSET -> throw new IllegalStateException("Key choice is not set for " + mapChangeKey);
            case ACCOUNT_ID_KEY -> mapChangeKey.accountIdKeyOrThrow();
            case TOKEN_RELATIONSHIP_KEY -> pairFrom(mapChangeKey.tokenRelationshipKeyOrThrow());
            case ENTITY_NUMBER_KEY -> new EntityNumber(mapChangeKey.entityNumberKeyOrThrow());
            case FILE_ID_KEY -> mapChangeKey.fileIdKeyOrThrow();
            case NFT_ID_KEY -> mapChangeKey.nftIdKeyOrThrow();
            case PROTO_BYTES_KEY -> new ProtoBytes(mapChangeKey.protoBytesKeyOrThrow());
            case PROTO_LONG_KEY -> new ProtoLong(mapChangeKey.protoLongKeyOrThrow());
            case PROTO_STRING_KEY -> new ProtoString(mapChangeKey.protoStringKeyOrThrow());
            case SCHEDULE_ID_KEY -> mapChangeKey.scheduleIdKeyOrThrow();
            case SLOT_KEY_KEY -> mapChangeKey.slotKeyKeyOrThrow();
            case TOKEN_ID_KEY -> mapChangeKey.tokenIdKeyOrThrow();
            case TOPIC_ID_KEY -> mapChangeKey.topicIdKeyOrThrow();
            case CONTRACT_ID_KEY -> mapChangeKey.contractIdKeyOrThrow();
            case PENDING_AIRDROP_ID_KEY -> mapChangeKey.pendingAirdropIdKeyOrThrow();
            case TIMESTAMP_SECONDS_KEY -> mapChangeKey.timestampSecondsKeyOrThrow();
            case SCHEDULED_ORDER_KEY -> mapChangeKey.scheduledOrderKeyOrThrow();
            case TSS_MESSAGE_MAP_KEY -> mapChangeKey.tssMessageMapKeyOrThrow();
            case TSS_VOTE_MAP_KEY -> mapChangeKey.tssVoteMapKeyOrThrow();
            case HINTS_PARTY_ID_KEY -> mapChangeKey.hintsPartyIdKeyOrThrow();
            case PREPROCESSING_VOTE_ID_KEY -> mapChangeKey.preprocessingVoteIdKeyOrThrow();
            case NODE_ID_KEY -> mapChangeKey.nodeIdKeyOrThrow();
            case CONSTRUCTION_NODE_ID_KEY -> mapChangeKey.constructionNodeIdKeyOrThrow();
        };
    }

    private static Object mapValueFor(@NonNull final MapChangeValue mapChangeValue) {
        return switch (mapChangeValue.valueChoice().kind()) {
            case UNSET -> throw new IllegalStateException("Value choice is not set for " + mapChangeValue);
            case ACCOUNT_VALUE -> mapChangeValue.accountValueOrThrow();
            case ACCOUNT_ID_VALUE -> mapChangeValue.accountIdValueOrThrow();
            case BYTECODE_VALUE -> mapChangeValue.bytecodeValueOrThrow();
            case FILE_VALUE -> mapChangeValue.fileValueOrThrow();
            case NFT_VALUE -> mapChangeValue.nftValueOrThrow();
            case PROTO_STRING_VALUE -> new ProtoString(mapChangeValue.protoStringValueOrThrow());
            case SCHEDULE_VALUE -> mapChangeValue.scheduleValueOrThrow();
            case SCHEDULE_ID_VALUE -> mapChangeValue.scheduleIdValueOrThrow();
            case SCHEDULE_LIST_VALUE -> mapChangeValue.scheduleListValueOrThrow();
            case SLOT_VALUE_VALUE -> mapChangeValue.slotValueValueOrThrow();
            case STAKING_NODE_INFO_VALUE -> mapChangeValue.stakingNodeInfoValueOrThrow();
            case TOKEN_VALUE -> mapChangeValue.tokenValueOrThrow();
            case TOKEN_RELATION_VALUE -> mapChangeValue.tokenRelationValueOrThrow();
            case TOPIC_VALUE -> mapChangeValue.topicValueOrThrow();
            case NODE_VALUE -> mapChangeValue.nodeValueOrThrow();
            case ACCOUNT_PENDING_AIRDROP_VALUE -> mapChangeValue.accountPendingAirdropValueOrThrow();
            case ROSTER_VALUE -> mapChangeValue.rosterValueOrThrow();
            case SCHEDULED_COUNTS_VALUE -> mapChangeValue.scheduledCountsValueOrThrow();
            case THROTTLE_USAGE_SNAPSHOTS_VALUE -> mapChangeValue.throttleUsageSnapshotsValue();
            case TSS_ENCRYPTION_KEYS_VALUE -> mapChangeValue.tssEncryptionKeysValue();
            case TSS_MESSAGE_VALUE -> mapChangeValue.tssMessageValueOrThrow();
            case TSS_VOTE_VALUE -> mapChangeValue.tssVoteValueOrThrow();
            case HINTS_KEY_SET_VALUE -> mapChangeValue.hintsKeySetValueOrThrow();
            case PREPROCESSING_VOTE_VALUE -> mapChangeValue.preprocessingVoteValueOrThrow();
            case CRS_PUBLICATION_VALUE -> mapChangeValue.crsPublicationValueOrThrow();
            case HISTORY_PROOF_VOTE_VALUE -> mapChangeValue.historyProofVoteValue();
            case HISTORY_SIGNATURE_VALUE -> mapChangeValue.historySignatureValue();
            case PROOF_KEY_SET_VALUE -> mapChangeValue.proofKeySetValue();
        };
    }

    private static EntityIDPair pairFrom(@NonNull final TokenAssociation tokenAssociation) {
        return new EntityIDPair(tokenAssociation.accountId(), tokenAssociation.tokenId());
    }

    /**
     * Load the address book from the given path, using {@link CryptoStatic#generateKeysAndCerts(AddressBook)}
     * to set its gossip certificates to the same certificates used by nodes in a test network.
     *
     * @param path the path to the address book file
     * @return the loaded address book
     */
    private static AddressBook loadLegacyBookWithGeneratedCerts(@NonNull final Path path) {
        requireNonNull(path);
        final var configFile = LegacyConfigPropertiesLoader.loadConfigFile(path.toAbsolutePath());
        try {
            final var addressBook = configFile.getAddressBook();
            CryptoStatic.generateKeysAndCerts(addressBook);
            return addressBook;
        } catch (Exception e) {
            throw new RuntimeException("Error generating keys and certs", e);
        }
    }
}
