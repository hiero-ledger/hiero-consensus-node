// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACTIVE_HINTS_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_FILES;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_LEDGER_ID;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NEXT_HINTS_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_PROOF_KEY_SETS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ROSTERS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ROSTER_STATE;
import static com.hedera.hapi.node.base.HederaFunctionality.HINTS_PARTIAL_SIGNATURE;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.stateNameOf;
import static com.hedera.node.app.hints.HintsService.maybeWeightsFrom;
import static com.hedera.node.app.history.HistoryLibrary.EMPTY_PUBLIC_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.APPLICATION_PROPERTIES;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.DATA_CONFIG_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SAVED_STATES_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.SWIRLDS_LOG;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.STATE_METADATA_FILE;
import static com.hedera.services.bdd.junit.hedera.utils.WorkingDirUtils.workingDirFor;
import static com.hedera.services.bdd.junit.support.validators.block.RootHashUtils.extractRootMnemonic;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toMap;
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
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.ProofKeySet;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RosterState;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.StreamingTreeHasher;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.blocks.impl.NaiveStreamingTreeHasher;
import com.hedera.node.app.config.BootstrapConfigProviderImpl;
import com.hedera.node.app.hapi.utils.CommonUtils;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.node.app.history.HistoryLibrary;
import com.hedera.node.app.history.impl.HistoryLibraryImpl;
import com.hedera.node.app.info.DiskStartupNetworks;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.roster.impl.ActiveRosters;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.hedera.services.bdd.spec.HapiSpec;
import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.state.MerkleNodeState;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Assertions;

/**
 * A validator that asserts the state changes in the block stream, when applied directly to a {@link MerkleNodeState}
 * initialized with the genesis {@link Service} schemas, result in the given root hash.
 */
public class StateChangesValidator implements BlockStreamValidator {

    private static final Logger logger = LogManager.getLogger(StateChangesValidator.class);
    private static final long DEFAULT_HINTS_THRESHOLD_DENOMINATOR = 2;
    private static final SplittableRandom RANDOM = new SplittableRandom(System.currentTimeMillis());
    private static final MerkleCryptography CRYPTO = TestMerkleCryptoFactory.getInstance();

    private static final int HASH_SIZE = 48;
    private static final int VISUALIZATION_HASH_DEPTH = 5;
    /**
     * The probability that the validator will verify an intermediate block proof; we always verify the first and
     * the last one that has an available block proof. (The blocks immediately preceding a freeze will not have proofs.)
     */
    private static final double PROOF_VERIFICATION_PROB = 0.05;

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    private final long hintsThresholdDenominator;
    private final Hash genesisStateHash;
    private final Path pathToNode0SwirldsLog;
    private final Bytes expectedRootHash;
    private final StateChangesSummary stateChangesSummary = new StateChangesSummary(new TreeMap<>());
    private final Map<String, Set<Object>> entityChanges = new LinkedHashMap<>();

    private Instant lastStateChangesTime;
    private StateChanges lastStateChanges;
    private MerkleNodeState state;

    @Nullable
    private Bytes ledgerId;

    /**
     * Initialized to the weights in the genesis roster, and updated to the weights in the active
     * hinTS construction as it is updated.
     */
    @Nullable
    private Map<Long, Long> activeWeights;

    @Nullable
    private final HintsLibrary hintsLibrary;

    @Nullable
    private final HistoryLibrary historyLibrary;

    @NonNull
    private final Supplier<IndirectProofSequenceValidator> proofSeqFactory;

    private final Map<Bytes, Set<Long>> signers = new HashMap<>();
    private final Map<Bytes, Long> blockNumbers = new HashMap<>();
    private final Map<Long, PreprocessedKeys> preprocessedKeys = new HashMap<>();
    private final Map<Long, Bytes> proofKeys = new HashMap<>();
    private final Map<Bytes, Roster> rosters = new HashMap<>();

    /**
     * The relevant context from a history proof construction.
     *
     * @param proverWeights the weights of the nodes in the prover history roster
     * @param targetWeights the weights of the nodes in the target history roster
     * @param proofKeys the proof keys of the nodes in the target history roster
     */
    private record HistoryContext(
            Map<Long, Long> proverWeights, Map<Long, Long> targetWeights, Map<Long, Bytes> proofKeys) {
        public Bytes targetBookHash(@NonNull final HistoryLibrary library) {
            requireNonNull(library);
            return HistoryLibrary.computeHash(
                    library,
                    targetWeights.keySet(),
                    targetWeights::get,
                    id -> proofKeys.getOrDefault(id, EMPTY_PUBLIC_KEY));
        }

        public byte[] wrapsMessageGiven(@NonNull final HistoryLibrary library, @NonNull final Bytes metadata) {
            final var targetAddressBook = HistoryLibrary.AddressBook.from(
                    new TreeMap<>(targetWeights()),
                    nodeId -> proofKeys.getOrDefault(nodeId, EMPTY_PUBLIC_KEY).toByteArray());
            return library.computeWrapsMessage(targetAddressBook, metadata.toByteArray());
        }

        public byte[][] publicKeysFor(@NonNull final List<Long> nodeIds) {
            return nodeIds.stream().map(proofKeys::get).map(Bytes::toByteArray).toArray(byte[][]::new);
        }
    }

    /**
     * The history proof contexts for each hinTS verification key when it first appeared in a next hinTS construction.
     */
    private final Map<Bytes, HistoryContext> vkContexts = new HashMap<>();

    /**
     * Tracks a sequence of indirect state proofs preceding a signed block proof. This field should <b>not</b>
     * be used to track non-contiguous state proofs.
     */
    @Nullable
    private IndirectProofSequenceValidator indirectProofSeq;

    public enum HintsEnabled {
        YES,
        NO
    }

    public enum HistoryEnabled {
        YES,
        NO
    }

    public enum StateProofsEnabled {
        YES,
        NO
    }

    public static void main(String[] args) {
        final var node0Dir = Paths.get("hedera-node/test-clients")
                .resolve(workingDirFor(0, "hapi"))
                .toAbsolutePath()
                .normalize();
        // 3 if debugging most PR checks, 4 if debugging the HAPI (Restart) check
        final long hintsThresholdDenominator = 3;
        final long shard = 11;
        final long realm = 12;
        final var validator = new StateChangesValidator(
                Bytes.fromHex(
                        "50ea5c2588457b952dba215bcefc5f54a1b87c298e5c0f2a534a8eb7177354126c55ee5c23319187e964443e4c17c007"),
                node0Dir.resolve("output/swirlds.log"),
                node0Dir.resolve("data/config/application.properties"),
                node0Dir.resolve("data/config"),
                16,
                HintsEnabled.YES,
                HistoryEnabled.YES,
                hintsThresholdDenominator,
                StateProofsEnabled.NO,
                shard,
                realm);
        final var blocks = BlockStreamAccess.BLOCK_STREAM_ACCESS.readBlocks(
                node0Dir.resolve("data/blockStreams/block-%d.%d.3".formatted(shard, realm)));
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

        final var node0 = subProcessNetwork.getRequiredNode(byNodeId(0));
        final boolean isHintsEnabled = spec.startupProperties().getBoolean("tss.hintsEnabled");
        final boolean isHistoryEnabled = spec.startupProperties().getBoolean("tss.historyEnabled");
        final int crsSize = spec.startupProperties().getInteger("tss.initialCrsParties");
        final boolean stateProofsEnabled = spec.startupProperties().getBoolean("block.stateproof.verification.enabled");
        return new StateChangesValidator(
                rootHash,
                node0.getExternalPath(SWIRLDS_LOG),
                node0.getExternalPath(APPLICATION_PROPERTIES),
                node0.getExternalPath(DATA_CONFIG_DIR),
                crsSize,
                isHintsEnabled ? HintsEnabled.YES : HintsEnabled.NO,
                isHistoryEnabled ? HistoryEnabled.YES : HistoryEnabled.NO,
                Optional.ofNullable(System.getProperty("hapi.spec.hintsThresholdDenominator"))
                        .map(Long::parseLong)
                        .orElse(DEFAULT_HINTS_THRESHOLD_DENOMINATOR),
                stateProofsEnabled ? StateProofsEnabled.YES : StateProofsEnabled.NO,
                spec.shard(),
                spec.realm());
    }

    public StateChangesValidator(
            @NonNull final Bytes expectedRootHash,
            @NonNull final Path pathToNode0SwirldsLog,
            @NonNull final Path pathToOverrideProperties,
            @NonNull final Path pathToUpgradeSysFilesLoc,
            final int crsSize,
            @NonNull final HintsEnabled hintsEnabled,
            @NonNull final HistoryEnabled historyEnabled,
            final long hintsThresholdDenominator,
            @NonNull final StateProofsEnabled stateProofsEnabled,
            final long shard,
            final long realm) {
        this.expectedRootHash = requireNonNull(expectedRootHash);
        this.pathToNode0SwirldsLog = requireNonNull(pathToNode0SwirldsLog);
        this.hintsThresholdDenominator = hintsThresholdDenominator;

        System.setProperty(
                "hedera.app.properties.path",
                pathToOverrideProperties.toAbsolutePath().toString());
        System.setProperty(
                "networkAdmin.upgradeSysFilesLoc",
                pathToUpgradeSysFilesLoc.toAbsolutePath().toString());
        System.setProperty("tss.hintsEnabled", "" + (hintsEnabled == HintsEnabled.YES));
        System.setProperty("tss.historyEnabled", "" + (historyEnabled == HistoryEnabled.YES));
        System.setProperty("tss.initialCrsParties", "" + crsSize);
        System.setProperty(
                "block.stateproof.verification.enabled", "" + (stateProofsEnabled == StateProofsEnabled.YES));
        System.setProperty("hedera.shard", String.valueOf(shard));
        System.setProperty("hedera.realm", String.valueOf(realm));

        unarchiveGenesisNetworkJson(pathToUpgradeSysFilesLoc);
        final var bootstrapConfig = new BootstrapConfigProviderImpl().getConfiguration();
        final var versionConfig = bootstrapConfig.getConfigData(VersionConfig.class);
        final var servicesVersion = versionConfig.servicesVersion();
        final var metrics = new NoOpMetrics();
        final var platformConfig = ServicesMain.buildPlatformConfig();
        final var hedera = ServicesMain.newHedera(platformConfig, metrics, Time.getCurrent());
        this.state = hedera.newStateRoot();
        hedera.initializeStatesApi(state, GENESIS, platformConfig);
        final var stateToBeCopied = state;
        state = state.copy();
        this.hintsLibrary = (hintsEnabled == HintsEnabled.YES) ? new HintsLibraryImpl() : null;
        this.historyLibrary = (historyEnabled == HistoryEnabled.YES) ? new HistoryLibraryImpl() : null;
        // get the state hash before applying the state changes from current block
        this.genesisStateHash = CRYPTO.digestTreeSync(stateToBeCopied.getRoot());
        this.proofSeqFactory =
                (stateProofsEnabled == StateProofsEnabled.YES) ? IndirectProofSequenceValidator::new : () -> null;

        logger.info("Registered all Service and migrated state definitions to version {}", servicesVersion);
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        logger.info("Beginning validation of expected root hash {}", expectedRootHash);
        var previousBlockHash = BlockStreamManager.ZERO_BLOCK_HASH;
        var startOfStateHash = requireNonNull(genesisStateHash).getBytes();

        final int n = blocks.size();
        final int lastVerifiableIndex =
                blocks.reversed().stream().filter(b -> b.items().getLast().hasBlockProof()).findFirst().stream()
                        .mapToInt(b ->
                                (int) b.items().getFirst().blockHeaderOrThrow().number())
                        .findFirst()
                        .orElseThrow();
        blocks.stream()
                .flatMap(b -> b.items().stream())
                .filter(BlockItem::hasStateChanges)
                .flatMap(i -> i.stateChangesOrThrow().stateChanges().stream())
                .filter(change -> change.stateId() == STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal())
                .map(change -> change.singletonUpdateOrThrow().hintsConstructionValueOrThrow())
                .filter(HintsConstruction::hasHintsScheme)
                .forEach(c -> preprocessedKeys.put(
                        c.constructionId(), c.hintsSchemeOrThrow().preprocessedKeysOrThrow()));
        final IncrementalStreamingHasher incrementalBlockHashes =
                new IncrementalStreamingHasher(CommonUtils.sha384DigestOrThrow(), List.of(), 0);
        incrementalBlockHashes.addLeaf(BlockStreamManager.ZERO_BLOCK_HASH.toByteArray());
        for (int i = 0; i < n; i++) {
            final var block = blocks.get(i);
            final var shouldVerifyProof = i == 0
                    || i == lastVerifiableIndex
                    || indirectProofSeq != null
                    || RANDOM.nextDouble() < PROOF_VERIFICATION_PROB;
            if (i != 0 && shouldVerifyProof) {
                final var stateToBeCopied = state;
                this.state = stateToBeCopied.copy();
                startOfStateHash =
                        CRYPTO.digestTreeSync(stateToBeCopied.getRoot()).getBytes();
            }
            final StreamingTreeHasher inputTreeHasher = new NaiveStreamingTreeHasher();
            final StreamingTreeHasher outputTreeHasher = new NaiveStreamingTreeHasher();
            final StreamingTreeHasher consensusHeaderHasher = new NaiveStreamingTreeHasher();
            final StreamingTreeHasher stateChangesHasher = new NaiveStreamingTreeHasher();
            final StreamingTreeHasher traceDataHasher = new NaiveStreamingTreeHasher();

            long firstBlockRound = -1;
            long eventNodeId = -1;
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
                if (shouldVerifyProof) {
                    hashSubTrees(
                            item,
                            inputTreeHasher,
                            outputTreeHasher,
                            consensusHeaderHasher,
                            stateChangesHasher,
                            traceDataHasher);
                }
                if (item.hasStateChanges()) {
                    final var changes = item.stateChangesOrThrow();
                    final var at = asInstant(changes.consensusTimestampOrThrow());
                    // (FUTURE) Re-enable after state change ordering is fixed as part of mega-map work
                    if (false && lastStateChanges != null && at.isBefore(requireNonNull(lastStateChangesTime))) {
                        Assertions.fail("State changes are not in chronological order - last changes were \n "
                                + lastStateChanges + "\ncurrent changes are \n  " + changes);
                    }
                    lastStateChanges = changes;
                    lastStateChangesTime = at;
                    applyStateChanges(item.stateChangesOrThrow());
                } else if (item.hasEventHeader()) {
                    eventNodeId = item.eventHeaderOrThrow().eventCoreOrThrow().creatorNodeId();
                } else if (item.hasSignedTransaction()) {
                    final var parts = TransactionParts.from(item.signedTransactionOrThrow());
                    if (parts.function() == HINTS_PARTIAL_SIGNATURE) {
                        final var op = parts.body().hintsPartialSignatureOrThrow();
                        final var all = signers.computeIfAbsent(op.message(), k -> new HashSet<>());
                        all.add(eventNodeId);
                        if (blockNumbers.containsKey(op.message())) {
                            logger.info(
                                    "#{} ({}...) now signed by {}",
                                    blockNumbers.get(op.message()),
                                    op.message().toString().substring(0, 8),
                                    all);
                        }
                    }
                }
            }
            assertNotNull(firstConsensusTimestamp, "No parseable timestamp found for block #" + i);

            if (i <= lastVerifiableIndex) {
                final var footer = block.items().get(block.items().size() - 2);
                assertTrue(footer.hasBlockFooter());
                final var lastBlockItem = block.items().getLast();
                assertTrue(lastBlockItem.hasBlockProof());
                final var blockProof = lastBlockItem.blockProofOrThrow();
                assertEquals(
                        previousBlockHash,
                        footer.blockFooterOrThrow().previousBlockRootHash(),
                        "Previous block hash mismatch for block " + blockProof.block());

                if (shouldVerifyProof) {
                    final var lastStateChange = lastStateChanges.stateChanges().getLast();
                    assertTrue(
                            lastStateChange.hasSingletonUpdate(),
                            "Final state change " + lastStateChange + " does not match expected singleton update type");
                    assertTrue(
                            lastStateChange.singletonUpdateOrThrow().hasBlockStreamInfoValue(),
                            "Final state change " + lastStateChange
                                    + " does not match final block BlockStreamInfo update type");

                    // The state changes hasher already incorporated the last state change, so compute its root hash
                    final var finalStateChangesHash =
                            stateChangesHasher.rootHash().join();

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
                    blockNumbers.put(
                            expectedBlockHash,
                            block.items().getFirst().blockHeaderOrThrow().number());
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
                } else {
                    final var nextBlock = blocks.get(i + 1);
                    final var nextBlockFooterIndex = nextBlock.items().size() - 2;
                    previousBlockHash = nextBlock
                            .items()
                            .get(nextBlockFooterIndex)
                            .blockFooterOrThrow()
                            .previousBlockRootHash();
                }

                incrementalBlockHashes.addLeaf(previousBlockHash.toByteArray());
            }
        }
        logger.info("Summary of changes by service:\n{}", stateChangesSummary);

        final var entityCounts =
                state.getWritableStates(EntityIdService.NAME).<EntityCounts>getSingleton(ENTITY_COUNTS_STATE_ID);
        assertEntityCountsMatch(entityCounts);

        // To make sure that VirtualMapMetadata is persisted after all changes from the block stream were applied
        state.copy();
        CRYPTO.digestTreeSync(state.getRoot());
        final var rootHash = requireNonNull(state.getHash()).getBytes();

        if (!expectedRootHash.equals(rootHash)) {
            final var expectedRootMnemonic = getMaybeLastHashMnemonics(pathToNode0SwirldsLog);
            if (expectedRootMnemonic == null) {
                throw new AssertionError("No expected root mnemonic found in " + pathToNode0SwirldsLog);
            }
            final var actualRootMnemonic = rootMnemonicFor(state.getRoot());
            final var errorMsg = new StringBuilder("Hashes did not match for the following states,");

            if (!expectedRootMnemonic.equals(actualRootMnemonic)) {
                errorMsg.append("\n    * ")
                        .append("root mnemonic ")
                        .append(" - expected ")
                        .append(expectedRootMnemonic)
                        .append(", was ")
                        .append(actualRootMnemonic);
            }
            Assertions.fail(errorMsg.toString());
        }
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

    private void hashSubTrees(
            final BlockItem item,
            final StreamingTreeHasher inputTreeHasher,
            final StreamingTreeHasher outputTreeHasher,
            final StreamingTreeHasher consensusHeaderHasher,
            final StreamingTreeHasher stateChangesHasher,
            final StreamingTreeHasher traceDataHasher) {
        final var itemSerialized = BlockItem.PROTOBUF.toBytes(item);
        final var digest = sha384DigestOrThrow();
        switch (item.item().kind()) {
            case EVENT_HEADER, ROUND_HEADER ->
                consensusHeaderHasher.addLeaf(ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            case SIGNED_TRANSACTION ->
                inputTreeHasher.addLeaf(ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            case TRANSACTION_RESULT, TRANSACTION_OUTPUT, BLOCK_HEADER ->
                outputTreeHasher.addLeaf(ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            case STATE_CHANGES ->
                stateChangesHasher.addLeaf(ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            case TRACE_DATA -> traceDataHasher.addLeaf(ByteBuffer.wrap(digest.digest(itemSerialized.toByteArray())));
            default -> {
                // Other items are not part of the input/output trees
            }
        }
    }

    private record RootAndSiblingHashes(Bytes blockRootHash, MerkleSiblingHash[] siblingHashes) {}

    private RootAndSiblingHashes computeBlockHash(
            final Timestamp blockTimestamp,
            final Bytes previousBlockHash,
            final IncrementalStreamingHasher prevBlockRootsHasher,
            final Bytes startOfBlockStateHash,
            final StreamingTreeHasher inputTreeHasher,
            final StreamingTreeHasher outputTreeHasher,
            final StreamingTreeHasher consensusHeaderHasher,
            final Bytes finalStateChangesHash,
            final StreamingTreeHasher traceDataHasher) {
        final var prevBlocksRootHash = Bytes.wrap(prevBlockRootsHasher.computeRootHash());
        final var consensusHeaderHash = consensusHeaderHasher.rootHash().join();
        final var inputTreeHash = inputTreeHasher.rootHash().join();
        final var outputTreeHash = outputTreeHasher.rootHash().join();
        final var traceDataHash = traceDataHasher.rootHash().join();

        // Compute depth five hashes
        final var depth5Node1 = BlockImplUtils.combine(previousBlockHash, prevBlocksRootHash);
        final var depth5Node2 = BlockImplUtils.combine(startOfBlockStateHash, consensusHeaderHash);
        final var depth5Node3 = BlockImplUtils.combine(inputTreeHash, outputTreeHash);
        final var depth5Node4 = BlockImplUtils.combine(finalStateChangesHash, traceDataHash);

        final var combinedNulls =
                BlockImplUtils.combine(BlockStreamManager.ZERO_BLOCK_HASH, BlockStreamManager.ZERO_BLOCK_HASH);
        // Nodes 5-8 for depth five are all combined null hashes, but enumerated for clarity
        final var depth5Node5 = combinedNulls;
        final var depth5Node6 = combinedNulls;
        final var depth5Node7 = combinedNulls;
        final var depth5Node8 = combinedNulls;

        // Compute depth four hashes
        final var depth4Node1 = BlockImplUtils.combine(depth5Node1, depth5Node2);
        final var depth4Node2 = BlockImplUtils.combine(depth5Node3, depth5Node4);
        final var depth4Node3 = BlockImplUtils.combine(depth5Node5, depth5Node6);
        final var depth4Node4 = BlockImplUtils.combine(depth5Node7, depth5Node8);

        // Compute depth three hashes
        final var depth3Node1 = BlockImplUtils.combine(depth4Node1, depth4Node2);
        final var depth3Node2 = BlockImplUtils.combine(depth4Node3, depth4Node4);

        // Compute depth two hashes (timestamp + last right sibling)
        final var depth2Node1 = noThrowSha384HashOf(Timestamp.PROTOBUF.toBytes(blockTimestamp));
        final var depth2Node2 = BlockImplUtils.combine(depth3Node1, depth3Node2);

        // Compute the block's root hash (depth 1)
        final var root = BlockImplUtils.combine(depth2Node1, depth2Node2);

        return new RootAndSiblingHashes(root, new MerkleSiblingHash[] {
            new MerkleSiblingHash(false, prevBlocksRootHash),
            new MerkleSiblingHash(false, depth5Node2),
            new MerkleSiblingHash(false, depth4Node2),
            new MerkleSiblingHash(false, depth2Node2)
        });
    }

    private boolean indirectProofsNeedVerification() {
        return indirectProofSeq != null && indirectProofSeq.containsIndirectProofs();
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
        assertEquals(
                footer.startOfBlockStateRootHash(),
                startOfStateHash,
                "Wrong start of state hash for block #" + blockNumber);

        // Our proof method will be different depending on whether this is a direct or indirect proof.
        // Direct proofs have a signed block proof; indirect proofs do not.
        if (!proof.hasSignedBlockProof()) {
            // This is an indirect proof, so a block state proof must be present
            assertTrue(
                    proof.hasBlockStateProof(),
                    "Indirect proof for block #%s is missing a block state proof".formatted(blockNumber));

            // If we don't currently have an indirect proof sequence, create one
            if (indirectProofSeq == null) {
                indirectProofSeq = proofSeqFactory.get();
            }

            // The indirect proof seq field could still be null if the factory doesn't produce a validator
            if (indirectProofSeq != null) {
                // We can't verify the indirect proof until we have a signed block proof, so store the indirect proof
                // for later verification and short-circuit the remainder of the proof verification
                indirectProofSeq.registerProof(
                        blockNumber,
                        proof,
                        expectedBlockHash,
                        previousBlockHash,
                        blockTimestamp,
                        expectedSiblingHashes);
            }
            return;
        } else if (indirectProofsNeedVerification()) {
            if (indirectProofSeq != null) {
                indirectProofSeq.registerProof(
                        blockNumber,
                        proof,
                        expectedBlockHash,
                        previousBlockHash,
                        blockTimestamp,
                        expectedSiblingHashes);
            }
        }

        // If hints are enabled, verify the signature using the hints library
        if (hintsLibrary != null) {
            // (FUTURE: GH issue #22676) Re-enable using the hints and history libraries to verify the
            // chain-of-trust. Necessary protobuf changes cause the following code not to compile, but
            // the method for retrieving the verification key hasn't yet been finalized, so this code
            // is disabled for a short time until such method is enumerated.
            //            final var signature = proof.signedBlockProofOrThrow().blockSignature();
            //            final var vk = proof.verificationKey();
            //            final boolean valid =
            //                    hintsLibrary.verifyAggregate(signature, expectedBlockHash, vk, 1,
            // hintsThresholdDenominator);
            //            if (!valid) {
            //                Assertions.fail(() -> "Invalid signature in proof (start round #" + firstRound + ") - " +
            // proof);
            //            } else {
            //                logger.info("Verified signature on #{}", proof.block());
            //            }
            //            // If history proofs are enabled, verify the history proof
            //            if (historyLibrary != null) {
            //                assertTrue(
            //                        proof.hasVerificationKeyProof(),
            //                        "No chain-of-trust for hinTS key in proof (start round #" + firstRound + ") - " +
            // proof);
            //                final var chainOfTrustProof = proof.verificationKeyProofOrThrow();
            //                switch (chainOfTrustProof.proof().kind()) {
            //                    case UNSET ->
            //                        Assertions.fail("Empty chain-of-trust for hinTS key in proof (start round #" +
            // firstRound
            //                                + ") - " + proof);
            //                    case AGGREGATED_NODE_SIGNATURES -> {
            //                        final var context = vkContexts.get(vk);
            //                        final var wrapsMessage = context.wrapsMessageGiven(historyLibrary, vk);
            //                        final var nonRecursiveProof = chainOfTrustProof.aggregatedNodeSignaturesOrThrow();
            //                        assertTrue(
            //                                historyLibrary.verifyAggregateSignature(
            //                                        wrapsMessage,
            //                                        context.publicKeysFor(nonRecursiveProof.signingNodeIds()),
            //                                        nonRecursiveProof.aggregatedSignature().toByteArray()),
            //                                "Invalid aggregated signature "
            //                                        + nonRecursiveProof.aggregatedSignature()
            //                                        + " in proof (start round #" + firstRound
            //                                        + ", signing node ids " + nonRecursiveProof.signingNodeIds()
            //                                        + ") on WRAPS message " + Bytes.wrap(wrapsMessage)
            //                                        + " with metadata " + vk
            //                                        + " - context " + context);
            //                    }
            //                    case WRAPS_PROOF -> {
            //                        final var compressedProof = chainOfTrustProof.wrapsProofOrThrow();
            //                        assertTrue(
            //                                historyLibrary.isValidWraps(compressedProof.toByteArray()),
            //                                "Invalid WRAPS proof (start round #" + firstRound + ") - " +
            // chainOfTrustProof);
            //                    }
            //                }
            //            }

            if (indirectProofsNeedVerification()) {
                logger.info("Verifying contiguous indirect proofs prior to block {}", blockNumber);
                indirectProofSeq.verify();
                indirectProofSeq = null; // Clear out the indirect proof sequence after verification
            }
        } else {
            final var expectedSignature = Bytes.wrap(noThrowSha384HashOf(expectedBlockHash.toByteArray()));
            assertEquals(
                    expectedSignature,
                    proof.signedBlockProofOrThrow().blockSignature(),
                    "Signature mismatch for " + proof);
        }
    }

    private String rootMnemonicFor(@NonNull final MerkleNode state) {
        final var sb = new StringBuilder();
        new MerkleTreeVisualizer(state).setDepth(VISUALIZATION_HASH_DEPTH).render(sb);
        logger.info("Replayed hashes:\n{}", sb);
        return extractRootMnemonic(sb.toString());
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
                case STATE_ADD, STATE_REMOVE -> {
                    // No-op
                }
                case SINGLETON_UPDATE -> {
                    final var singletonState = writableStates.getSingleton(stateId);
                    final var singleton = BlockStreamUtils.singletonPutFor(stateChange.singletonUpdateOrThrow());
                    singletonState.put(singleton);
                    stateChangesSummary.countSingletonPut(serviceName, stateId);
                    if (stateChange.stateId() == STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal()) {
                        final var construction = (HintsConstruction) singleton;
                        if (construction.hasHintsScheme()) {
                            final var nextScheme = construction.hintsSchemeOrThrow();
                            final var nextVk =
                                    nextScheme.preprocessedKeysOrThrow().verificationKey();
                            requireNonNull(activeWeights);
                            final var candidateRoster = rosters.get(construction.targetRosterHash());
                            final var nextVkContext = new HistoryContext(
                                    Map.copyOf(activeWeights),
                                    ActiveRosters.weightsFrom(candidateRoster),
                                    Map.copyOf(proofKeys));
                            vkContexts.put(nextVk, nextVkContext);
                        }
                    } else if (stateChange.stateId() == STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal()) {
                        final var construction = (HintsConstruction) singleton;
                        if (construction.hasHintsScheme()) {
                            final var proverWeights = Map.copyOf(requireNonNull(activeWeights));
                            activeWeights = requireNonNull(maybeWeightsFrom(construction));
                            final var activeVk = construction
                                    .hintsSchemeOrThrow()
                                    .preprocessedKeysOrThrow()
                                    .verificationKey();
                            final var activeVkContext =
                                    new HistoryContext(proverWeights, Map.copyOf(activeWeights), Map.copyOf(proofKeys));
                            vkContexts.put(activeVk, activeVkContext);
                        }
                    } else if (stateChange.stateId() == STATE_ID_LEDGER_ID.protoOrdinal()) {
                        ledgerId = ((ProtoBytes) singleton).value();
                    } else if (stateChange.stateId() == STATE_ID_ROSTER_STATE.protoOrdinal()) {
                        if (activeWeights == null) {
                            final var rosterState = (RosterState) singleton;
                            final var activeRoster = rosters.get(
                                    rosterState.roundRosterPairs().getFirst().activeRosterHash());
                            activeWeights = activeRoster.rosterEntries().stream()
                                    .collect(toMap(RosterEntry::nodeId, RosterEntry::weight));
                        }
                    }
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
                    if (stateId == STATE_ID_ROSTERS.protoOrdinal()) {
                        rosters.put(((ProtoBytes) key).value(), (Roster) value);
                    } else if (stateId == STATE_ID_PROOF_KEY_SETS.protoOrdinal()) {
                        proofKeys.put(((NodeId) key).id(), ((ProofKeySet) value).key());
                    }
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

    /**
     * If the given path does not contain the genesis network JSON, recovers it from the archive directory.
     *
     * @param path the path to the network directory
     * @throws IllegalStateException if the genesis network JSON cannot be found
     * @throws UncheckedIOException if an I/O error occurs
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

    private static @Nullable String getMaybeLastHashMnemonics(final Path path) {
        String rootMnemonicLine = null;
        try {
            final var lines = Files.readAllLines(path);
            for (final var line : lines) {
                if (line.startsWith("(root)")) {
                    rootMnemonicLine = line;
                    break;
                }
            }
        } catch (IOException e) {
            logger.error("Could not read root mnemonic from {}", path, e);
            return null;
        }
        logger.info("Read root mnemonic:\n{}", rootMnemonicLine);
        return rootMnemonicLine == null ? null : extractRootMnemonic(rootMnemonicLine);
    }

    private static @NonNull SortedMap<Long, Long> weightsFrom(@NonNull final Roster roster) {
        return requireNonNull(roster).rosterEntries().stream()
                .collect(toMap(RosterEntry::nodeId, RosterEntry::weight, (a, b) -> a, TreeMap::new));
    }
}
