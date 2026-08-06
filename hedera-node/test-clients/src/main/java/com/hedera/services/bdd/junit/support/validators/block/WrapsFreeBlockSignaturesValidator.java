// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACTIVE_HINTS_CONSTRUCTION;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_CRS_STATE;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_HINTS_KEY_SETS;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_LEDGER_ID;
import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_NEXT_HINTS_CONSTRUCTION;
import static com.hedera.hapi.node.base.HederaFunctionality.HINTS_PARTIAL_SIGNATURE;
import static com.hedera.hapi.node.base.HederaFunctionality.LEDGER_ID_PUBLICATION;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static com.hedera.node.app.hints.impl.RsaContext.CONSTRUCTION_ID;
import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.cryptography.hints.HintsLibraryBridge;
import com.hedera.cryptography.tss.TSS;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.SingletonUpdateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.StateIdentifier;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsKeySet;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.BlockStreamManagerImpl;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.PathsConfig;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Assertions;

/**
 * Standalone validator for wrap-free TSS block signatures.
 *
 * <p>This intentionally implements only the narrow {@link StateChangesValidator} proof path where WRAPS is disabled:
 * direct block signatures are verified as {@code verificationKey || aggregateSig} with
 * {@link HintsLibrary#verifyAggregate(Bytes, Bytes, Bytes, long, long)}.
 */
@SuppressWarnings("removal")
public class WrapsFreeBlockSignaturesValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(WrapsFreeBlockSignaturesValidator.class);
    private static final int MAX_SUBSET_SEARCH_PARTIALS = 16;
    private static final int HINTS_SIGNATURE_LENGTH = 1632;
    private static final boolean DUMP_INVALID_AGGREGATE_VECTORS =
            Boolean.getBoolean("hints.dumpInvalidAggregateVectors")
                    || Boolean.parseBoolean(System.getenv("HINTS_DUMP_INVALID_AGGREGATE_VECTORS"));

    private final long hintsThresholdDenominator;
    private final HintsLibrary hintsLibrary;
    private final Metrics metrics;

    private VirtualMapStateImpl state;
    private Instant lastStateChangesTime;
    private StateChanges lastStateChanges;
    private int directProofsVerified;
    private int invalidDirectProofs;
    private int indirectProofSequencesVerified;
    private int prooflessBlocks;
    private int canonicalBlockHashMismatches;
    private final Map<Bytes, Long> blockNumbers = new HashMap<>();
    private final Map<Long, ConstructionSnapshot> constructionSnapshots = new HashMap<>();
    private final Map<PartyKey, HintsKeySet> hintsKeySets = new HashMap<>();
    private final Map<Long, String> lastPreprocessDiagnostic = new HashMap<>();
    private final Map<Bytes, Map<Long, ObservedPartials>> observedPartials = new HashMap<>();
    private final Map<Bytes, ProofSignature> proofSignatures = new HashMap<>();
    private final List<Long> pendingIndirectProofBlocks = new ArrayList<>();
    private final Set<String> dumpedInvalidAggregateVectors = new HashSet<>();
    private long lastHintsBridgeConstructionId = Long.MIN_VALUE;

    @Nullable
    private Bytes currentCrs;

    @Nullable
    private Bytes ledgerIdFromState;

    @Nullable
    private IndirectProofSequenceValidator indirectProofSeq;

    private record ConstructionSnapshot(
            long constructionId,
            @NonNull Bytes aggregationKey,
            @NonNull Bytes verificationKey,
            @NonNull Map<Long, Integer> partyIds,
            @NonNull Map<Long, Long> nodeWeights,
            long totalWeight) {
        private ConstructionSnapshot {
            requireNonNull(aggregationKey);
            requireNonNull(verificationKey);
            requireNonNull(partyIds);
            requireNonNull(nodeWeights);
        }
    }

    private record PartyKey(int partyId, int numParties) {}

    private record PartialValidation(
            @Nullable Boolean valid,
            @Nullable Integer partyId,
            long weight,
            @NonNull String status) {
        private PartialValidation {
            requireNonNull(status);
        }
    }

    private record ObservedPartial(
            long nodeId, @NonNull Bytes signature, @NonNull PartialValidation validation) {
        private ObservedPartial {
            requireNonNull(signature);
            requireNonNull(validation);
        }
    }

    private static final class ObservedPartials {
        private final long constructionId;
        private final Map<Long, ObservedPartial> byNode = new HashMap<>();
        private int lastDiagnosticSize = -1;

        private ObservedPartials(final long constructionId) {
            this.constructionId = constructionId;
        }

        private void add(@NonNull final ObservedPartial partial) {
            byNode.putIfAbsent(partial.nodeId(), partial);
        }
    }

    private record ProofSignature(
            long blockNumber,
            @NonNull Bytes blockHash,
            @NonNull Bytes verificationKey,
            @NonNull Bytes aggregateSignature,
            boolean valid) {
        private ProofSignature {
            requireNonNull(blockHash);
            requireNonNull(verificationKey);
            requireNonNull(aggregateSignature);
        }
    }

    private record StreamHashContext(
            @NonNull Bytes previousBlockHash, @NonNull IncrementalStreamingHasher incrementalBlockHashes) {
        private StreamHashContext {
            requireNonNull(previousBlockHash);
            requireNonNull(incrementalBlockHashes);
        }
    }

    public static void main(@NonNull final String[] args) {
        final var helpRequested = args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]));
        if (helpRequested || args.length < 2) {
            printUsage();
            if (!helpRequested) {
                System.exit(2);
            }
            return;
        }

        final var blockStreamDirs = new ArrayList<Path>(args.length - 1);
        for (int i = 0; i < args.length - 1; i++) {
            blockStreamDirs.add(Paths.get(args[i]).toAbsolutePath().normalize());
        }
        final var hintsThresholdDenominator = Long.parseLong(args[args.length - 1]);

        final var blockEpochs = new ArrayList<List<Block>>(blockStreamDirs.size());
        for (int i = 0; i < blockStreamDirs.size(); i++) {
            final var blocks = readBlocksFrom(blockStreamDirs.get(i));
            blockEpochs.add(blocks);
            System.out.printf(
                    "Loaded epoch %d with %d block(s) from %s%n", i + 1, blocks.size(), blockStreamDirs.get(i));
        }
        final var validator = new WrapsFreeBlockSignaturesValidator(hintsThresholdDenominator);
        validator.validateBlockEpochs(blockEpochs);

        System.out.printf(
                "Validated %d block stream epoch(s); verified %d direct block signature(s), "
                        + "found %d invalid direct block signature(s), "
                        + "%d indirect proof sequence(s); %d block(s) had no proof; "
                        + "%d locally computed block hash(es) differed from the canonical stream reconstruction.%n",
                blockEpochs.size(),
                validator.directProofsVerified,
                validator.invalidDirectProofs,
                validator.indirectProofSequencesVerified,
                validator.prooflessBlocks,
                validator.canonicalBlockHashMismatches);
    }

    public WrapsFreeBlockSignaturesValidator(final long hintsThresholdDenominator) {
        this.hintsThresholdDenominator = hintsThresholdDenominator;

        metrics = new NoOpMetrics();
        final var platformConfig = ServicesMain.buildPlatformConfig();
        final var pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final var fileSystemManager = new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        final var merkleDbConfig = platformConfig.getConfigData(MerkleDbConfig.class);
        final var dsBuilder =
                new MerkleDbDataSourceBuilder(platformConfig, fileSystemManager, merkleDbConfig.initialCapacity());
        final var virtualMap = new VirtualMap(dsBuilder, platformConfig);
        state = new VirtualMapStateImpl(virtualMap, metrics);
        this.hintsLibrary = new HintsLibraryImpl();
        logger.info("Initialized wrap-free signature validator with an empty binary state");
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        validateBlockEpochs(List.of(blocks));
    }

    /**
     * Validates one or more consecutive block stream epochs while replaying their state changes continuously. At each
     * epoch boundary, the first block of the new epoch must contain the legacy {@code BlockInfo} used during cutover to
     * seed its previous block hash and incremental block hash tree.
     *
     * @param blockEpochs block stream epochs in chronological order, with blocks ordered within each epoch
     */
    public void validateBlockEpochs(@NonNull final List<List<Block>> blockEpochs) {
        requireNonNull(blockEpochs);
        if (blockEpochs.isEmpty()) {
            Assertions.fail("No block stream epochs to validate");
        }
        for (int i = 0; i < blockEpochs.size(); i++) {
            requireNonNull(blockEpochs.get(i), "Block stream epoch " + (i + 1) + " is null");
            if (blockEpochs.get(i).isEmpty()) {
                Assertions.fail("Block stream epoch " + (i + 1) + " has no blocks to validate");
            }
        }

        final var blockCount = blockEpochs.stream().mapToInt(List::size).sum();
        logger.info(
                "Beginning wrap-free signature validation of {} block(s) across {} epoch(s)",
                blockCount,
                blockEpochs.size());
        final var discoveredLedgerId = discoverLedgerIdFrom(blockEpochs);
        if (discoveredLedgerId != null) {
            ledgerIdFromState = discoveredLedgerId;
            logger.info(
                    "Discovered {}-byte ledger id for block #0 signature verification", discoveredLedgerId.length());
        }
        var previousBlockHash = HASH_OF_ZERO;
        var incrementalBlockHashes = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);

        for (int epochIndex = 0; epochIndex < blockEpochs.size(); epochIndex++) {
            final var blocks = blockEpochs.get(epochIndex);
            if (epochIndex > 0) {
                assertNoTrailingIndirectProofs(epochIndex);
                final var cutoverContext = cutoverHashContextFrom(blocks.getFirst());
                previousBlockHash = cutoverContext.previousBlockHash();
                incrementalBlockHashes = cutoverContext.incrementalBlockHashes();
                logger.info(
                        "Beginning post-cutover epoch {} at block #{} with previous block hash {}",
                        epochIndex + 1,
                        blockNumberOf(blocks.getFirst()),
                        previousBlockHash);
            }

            for (int i = 0, n = blocks.size(); i < n; i++) {
                final var startOfStateHash = hashCurrentStateAndAdvanceMutableCopy();

                final var block = blocks.get(i);
                final var blockNumber = blockNumberOf(block);
                final IncrementalStreamingHasher inputTreeHasher =
                        new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
                final IncrementalStreamingHasher outputTreeHasher =
                        new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
                final IncrementalStreamingHasher consensusHeaderHasher =
                        new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
                final IncrementalStreamingHasher stateChangesHasher =
                        new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);
                final IncrementalStreamingHasher traceDataHasher =
                        new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);

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
                    hashSubTrees(
                            item,
                            inputTreeHasher,
                            outputTreeHasher,
                            consensusHeaderHasher,
                            stateChangesHasher,
                            traceDataHasher);
                    if (item.hasStateChanges()) {
                        final var changes = item.stateChangesOrThrow();
                        final var at = asInstant(changes.consensusTimestampOrThrow());
                        // (FUTURE) Re-enable after state change ordering is fixed as part of mega-map work.
                        if (false && lastStateChanges != null && at.isBefore(requireNonNull(lastStateChangesTime))) {
                            Assertions.fail("State changes are not in chronological order - last changes were \n "
                                    + lastStateChanges + "\ncurrent changes are \n  " + changes);
                        }
                        lastStateChanges = changes;
                        lastStateChangesTime = at;
                        applyStateChanges(changes);
                    } else if (item.hasEventHeader()) {
                        eventNodeId =
                                item.eventHeaderOrThrow().eventCoreOrThrow().creatorNodeId();
                    } else if (item.hasSignedTransaction()) {
                        final var parts = TransactionParts.from(item.signedTransactionOrThrow());
                        if (parts.function() == HINTS_PARTIAL_SIGNATURE) {
                            final var op = parts.body().hintsPartialSignatureOrThrow();
                            observeHintsPartialSignature(eventNodeId, op);
                        } else if (parts.function() == LEDGER_ID_PUBLICATION) {
                            final var ledgerIdPublication = parts.body().ledgerIdPublicationOrThrow();
                            ledgerIdFromState = ledgerIdPublication.ledgerId();
                            final int k =
                                    ledgerIdPublication.nodeContributions().size();
                            final long[] nodeIds = new long[k];
                            final long[] weights = new long[k];
                            final byte[][] publicKeys = new byte[k][];
                            for (int j = 0; j < k; j++) {
                                final var contribution =
                                        ledgerIdPublication.nodeContributions().get(j);
                                nodeIds[j] = contribution.nodeId();
                                weights[j] = contribution.weight();
                                publicKeys[j] = contribution.historyProofKey().toByteArray();
                            }
                            TSS.setAddressBook(publicKeys, weights, nodeIds);
                        }
                    }
                }
                assertNotNull(firstConsensusTimestamp, "No parseable timestamp found for block #" + blockNumber);

                final var footer = footerFrom(block);
                if (footer == null) {
                    prooflessBlocks++;
                    final var recoveredBlockHash = i + 1 < n ? previousBlockHashFrom(blocks.get(i + 1)) : null;
                    if (recoveredBlockHash == null) {
                        logger.warn(
                                "Skipping block #{} because it has no footer and its hash could not be recovered",
                                blockNumber);
                    } else {
                        previousBlockHash = recoveredBlockHash;
                        blockNumbers.put(previousBlockHash, blockNumber);
                        incrementalBlockHashes.addNodeByHash(previousBlockHash.toByteArray());
                        logger.warn(
                                "Block #{} had no footer or BlockProof; recovered its hash {} from the next block "
                                        + "footer",
                                blockNumber,
                                recoveredBlockHash);
                    }
                    continue;
                }
                assertEquals(
                        previousBlockHash,
                        footer.previousBlockRootHash(),
                        "Previous block hash mismatch for block " + blockNumber);
                assertEquals(
                        startOfStateHash,
                        footer.startOfBlockStateRootHash(),
                        "Wrong start of block state hash for block #" + blockNumber);

                final var expectedRootAndSiblings = computeBlockHash(
                        firstConsensusTimestamp,
                        previousBlockHash,
                        incrementalBlockHashes,
                        startOfStateHash,
                        inputTreeHasher,
                        outputTreeHasher,
                        consensusHeaderHasher,
                        stateChangesHasher,
                        traceDataHasher);
                final var expectedBlockHash = expectedRootAndSiblings.blockRootHash();
                final var persistedBlockHash = persistedBlockHashFrom(block);
                final var hashFromNextFooter = i + 1 < n ? previousBlockHashFrom(blocks.get(i + 1)) : null;
                final var blockHash = hashFromNextFooter != null
                        ? hashFromNextFooter
                        : persistedBlockHash != null ? persistedBlockHash : expectedBlockHash;
                if (!blockHash.equals(expectedBlockHash)) {
                    canonicalBlockHashMismatches++;
                    logger.warn(
                            "Computed hash {} for block #{} does not match canonical stream reconstruction {}; "
                                    + "using the canonical hash for proof and chain validation",
                            expectedBlockHash,
                            blockNumber,
                            blockHash);
                }
                blockNumbers.put(expectedBlockHash, blockNumber);
                blockNumbers.put(blockHash, blockNumber);
                final var proof = proofFrom(block);
                if (proof != null) {
                    var proofBlockHash = blockHash;
                    if (!expectedBlockHash.equals(blockHash) && proof.hasSignedBlockProof()) {
                        if (directProofSignatureValidFor(proof, expectedBlockHash)) {
                            proofBlockHash = expectedBlockHash;
                            logger.warn(
                                    "Signature for block #{} validates its locally computed hash {}, while the "
                                            + "following chain uses {}",
                                    blockNumber,
                                    expectedBlockHash,
                                    blockHash);
                        } else if (!directProofSignatureValidFor(proof, blockHash)
                                && persistedBlockHash != null
                                && !persistedBlockHash.equals(blockHash)
                                && directProofSignatureValidFor(proof, persistedBlockHash)) {
                            proofBlockHash = persistedBlockHash;
                            logger.warn(
                                    "Signature for block #{} validates persisted hash {} instead of canonical "
                                            + "chain hash {}",
                                    blockNumber,
                                    persistedBlockHash,
                                    blockHash);
                        }
                    }
                    validateBlockProof(
                            blockNumber,
                            firstBlockRound,
                            footer,
                            proof,
                            proofBlockHash,
                            startOfStateHash,
                            previousBlockHash,
                            firstConsensusTimestamp,
                            expectedRootAndSiblings.siblingHashes());
                } else {
                    prooflessBlocks++;
                    logger.warn("Block #{} had no BlockProof", blockNumber);
                }

                previousBlockHash = blockHash;
                incrementalBlockHashes.addNodeByHash(previousBlockHash.toByteArray());
            }
        }

        assertNoTrailingIndirectProofs(blockEpochs.size());
        logger.info(
                "Validated {} block stream epoch(s), {} direct signed proof(s), {} indirect proof sequence(s), "
                        + "with {} proofless block(s) and {} canonical block hash mismatch(es)",
                blockEpochs.size(),
                directProofsVerified,
                indirectProofSequencesVerified,
                prooflessBlocks,
                canonicalBlockHashMismatches);
    }

    private void assertNoTrailingIndirectProofs(final int epochNumber) {
        if (indirectProofSeq != null && indirectProofSeq.containsIndirectProofs()) {
            Assertions.fail("Cannot verify trailing indirect proof sequence in epoch " + epochNumber
                    + " without a following signed block proof");
        }
    }

    private static StreamHashContext cutoverHashContextFrom(@NonNull final Block firstPostCutoverBlock) {
        final var blockInfo = BlockStreamAccess.computeSingletonValueFromUpdates(
                List.of(firstPostCutoverBlock),
                SingletonUpdateChange::blockInfoValue,
                StateIdentifier.STATE_ID_BLOCKS.protoOrdinal());
        if (blockInfo == null
                || blockInfo.previousWrappedRecordBlockRootHash() == null
                || blockInfo.previousWrappedRecordBlockRootHash().equals(Bytes.EMPTY)) {
            throw new AssertionError("Post-cutover epoch begins with block #" + blockNumberOf(firstPostCutoverBlock)
                    + " but its first block has no BlockInfo with wrapped record block hashes");
        }
        final var incrementalBlockHashes = new IncrementalStreamingHasher(
                sha384DigestOrThrow(),
                blockInfo.wrappedIntermediatePreviousBlockRootHashes().stream()
                        .map(Bytes::toByteArray)
                        .toList(),
                blockInfo.wrappedIntermediateBlockRootsLeafCount());
        incrementalBlockHashes.addNodeByHash(
                blockInfo.previousWrappedRecordBlockRootHash().toByteArray());
        return new StreamHashContext(blockInfo.previousWrappedRecordBlockRootHash(), incrementalBlockHashes);
    }

    private static @Nullable Bytes discoverLedgerIdFrom(@NonNull final List<List<Block>> blockEpochs) {
        Bytes ledgerId = null;
        for (final var epoch : blockEpochs) {
            for (final var block : epoch) {
                for (final var item : block.items()) {
                    if (item.hasStateChanges()) {
                        for (final var stateChange : item.stateChangesOrThrow().stateChanges()) {
                            if (stateChange.stateId() == STATE_ID_LEDGER_ID.protoOrdinal()
                                    && stateChange.hasSingletonUpdate()
                                    && stateChange.singletonUpdateOrThrow().hasBytesValue()) {
                                final var candidate =
                                        stateChange.singletonUpdateOrThrow().bytesValueOrThrow();
                                if (!candidate.equals(Bytes.EMPTY)) {
                                    ledgerId = candidate;
                                }
                            }
                        }
                    } else if (item.hasSignedTransaction()) {
                        final var parts = TransactionParts.from(item.signedTransactionOrThrow());
                        if (parts.function() == LEDGER_ID_PUBLICATION) {
                            final var candidate =
                                    parts.body().ledgerIdPublicationOrThrow().ledgerId();
                            if (!candidate.equals(Bytes.EMPTY)) {
                                ledgerId = candidate;
                            }
                        }
                    }
                }
            }
        }
        return ledgerId;
    }

    private Bytes hashCurrentStateAndAdvanceMutableCopy() {
        final VirtualMap stateAtStartOfBlock = state.getRoot();
        final var mutableCopy = stateAtStartOfBlock.copy();
        state = new VirtualMapStateImpl(mutableCopy, metrics);
        final var hash = requireNonNull(stateAtStartOfBlock.getHash()).getBytes();
        stateAtStartOfBlock.release();
        return hash;
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
                "Wrong start of block state hash for block #" + blockNumber);

        if (proof.hasSignedRecordFileProof()) {
            Assertions.fail("Wrap-free validator cannot verify SignedRecordFileProof for block #" + blockNumber);
        }
        if (!proof.hasSignedBlockProof()) {
            assertTrue(
                    proof.hasBlockStateProof(),
                    "Indirect proof for block #%s is missing a block state proof".formatted(blockNumber));
            if (indirectProofSeq == null) {
                indirectProofSeq = new IndirectProofSequenceValidator();
            }
            indirectProofSeq.registerProof(
                    blockNumber, proof, expectedBlockHash, previousBlockHash, blockTimestamp, expectedSiblingHashes);
            pendingIndirectProofBlocks.add(blockNumber);
            return;
        } else if (indirectProofSeq != null && indirectProofSeq.containsIndirectProofs()) {
            indirectProofSeq.registerProof(
                    blockNumber, proof, expectedBlockHash, previousBlockHash, blockTimestamp, expectedSiblingHashes);
        }

        verifySignedBlockProof(firstRound, proof, expectedBlockHash);

        if (indirectProofSeq != null && indirectProofSeq.containsIndirectProofs()) {
            logger.info("Verifying contiguous indirect proofs prior to block {}", blockNumber);
            indirectProofSeq.verify();
            for (final var pendingBlockNumber : pendingIndirectProofBlocks) {
                System.out.printf("#%d proof valid: yes%n", pendingBlockNumber);
            }
            pendingIndirectProofBlocks.clear();
            indirectProofSeq = null;
            indirectProofSequencesVerified++;
        }
    }

    private void observeHintsPartialSignature(
            final long eventNodeId, @NonNull final HintsPartialSignatureTransactionBody op) {
        requireNonNull(op);
        if (op.constructionId() == CONSTRUCTION_ID) {
            return;
        }
        final var validation = validateObservedPartial(eventNodeId, op);
        observedPartials
                .computeIfAbsent(op.message(), ignore -> new HashMap<>())
                .computeIfAbsent(op.constructionId(), ObservedPartials::new)
                .add(new ObservedPartial(eventNodeId, op.partialSignature(), validation));

        final var constructionVkHash = constructionVkHashFor(op.constructionId());
        final var signedBlock = blockNumbers.get(op.message());
        if (signedBlock != null) {
            System.out.printf(
                    "  -> #%d now signed by node%d " + "(hinTS constructionId=%d, constructionVkHash=%s, %s)%n",
                    signedBlock, eventNodeId, op.constructionId(), constructionVkHash, validation.status());
        } else {
            System.out.printf(
                    "  -> unknown hinTS block hash %s... now signed by node%d "
                            + "(constructionId=%d, constructionVkHash=%s, %s)%n",
                    shortHash(op.message()), eventNodeId, op.constructionId(), constructionVkHash, validation.status());
        }
        printOfflineAggregateDiagnosticIfReady(op.message(), op.constructionId());
    }

    private PartialValidation validateObservedPartial(
            final long nodeId, @NonNull final HintsPartialSignatureTransactionBody op) {
        final var snapshot = constructionSnapshots.get(op.constructionId());
        if (snapshot == null) {
            return new PartialValidation(null, null, 0, "partialValid=? (missing construction snapshot)");
        }
        final var crs = currentCrs();
        if (crs == null) {
            return new PartialValidation(null, null, 0, "partialValid=? (missing CRS)");
        }
        final var partyId = snapshot.partyIds().get(nodeId);
        if (partyId == null) {
            return new PartialValidation(false, null, 0, "partialValid=no (node has no party id)");
        }
        final var weight = snapshot.nodeWeights().getOrDefault(nodeId, 0L);
        useHintsBridgeFor(snapshot.constructionId());
        final var valid =
                hintsLibrary.verifyBls(crs, op.partialSignature(), op.message(), snapshot.aggregationKey(), partyId);
        return new PartialValidation(
                valid,
                partyId,
                weight,
                "partialValid=%s, party=%d, weight=%d".formatted(valid ? "yes" : "no", partyId, weight));
    }

    private void verifySignedBlockProof(
            final long firstRound, @NonNull final BlockProof proof, @NonNull final Bytes expectedBlockHash) {
        final var signature = proof.signedBlockProofOrThrow().blockSignature();
        final boolean valid;
        String proofVkHash = null;
        Bytes aggregateSignature = null;
        Bytes verificationKey = null;
        if (signature.length() == HintsLibraryImpl.VK_LENGTH + HINTS_SIGNATURE_LENGTH) {
            final var vk = signature.slice(0, HintsLibraryImpl.VK_LENGTH);
            final var sig = signature.slice(HintsLibraryImpl.VK_LENGTH, HintsLibraryImpl.SIGNATURE_LENGTH);
            proofVkHash = shortSha384Hash(vk);
            verificationKey = vk;
            aggregateSignature = sig;
            valid = hintsLibrary.verifyAggregate(sig, expectedBlockHash, vk, 1, hintsThresholdDenominator);
        } else {
            requireNonNull(
                    ledgerIdFromState,
                    "Ledger id not available for composite TSS signature verification on block #" + proof.block());
            valid = TSS.verifyTSS(
                    ledgerIdFromState.toByteArray(), signature.toByteArray(), expectedBlockHash.toByteArray());
        }
        if (proofVkHash == null) {
            System.out.printf("#%d proof valid: %s%n", proof.block(), valid ? "yes" : "no");
        } else {
            System.out.printf(
                    "#%d proof valid: %s (proofVkHash=%s, proofSigHash=%s)%n",
                    proof.block(),
                    valid ? "yes" : "no",
                    proofVkHash,
                    shortSha384Hash(requireNonNull(aggregateSignature)));
            proofSignatures.put(
                    expectedBlockHash,
                    new ProofSignature(
                            proof.block(),
                            expectedBlockHash,
                            requireNonNull(verificationKey),
                            aggregateSignature,
                            valid));
            observedPartials
                    .getOrDefault(expectedBlockHash, Map.of())
                    .keySet()
                    .forEach(constructionId ->
                            printOfflineAggregateDiagnosticIfReady(expectedBlockHash, constructionId));
        }
        if (valid) {
            directProofsVerified++;
            logger.info("Verified wrap-free signature on block #{}", proof.block());
        } else {
            invalidDirectProofs++;
            logger.warn("Invalid wrap-free signature on block #{} from start round #{}", proof.block(), firstRound);
        }
    }

    private boolean directProofSignatureValidFor(@NonNull final BlockProof proof, @NonNull final Bytes blockHash) {
        final var signature = proof.signedBlockProofOrThrow().blockSignature();
        if (signature.length() != HintsLibraryImpl.VK_LENGTH + HINTS_SIGNATURE_LENGTH) {
            return false;
        }
        final var verificationKey = signature.slice(0, HintsLibraryImpl.VK_LENGTH);
        final var aggregateSignature = signature.slice(HintsLibraryImpl.VK_LENGTH, HintsLibraryImpl.SIGNATURE_LENGTH);
        return hintsLibrary.verifyAggregate(
                aggregateSignature, blockHash, verificationKey, 1, hintsThresholdDenominator);
    }

    private void applyStateChanges(@NonNull final StateChanges stateChanges) {
        BinaryStateChangeParser.applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges));
        captureStateFrom(stateChanges);
    }

    private void captureStateFrom(@NonNull final StateChanges stateChanges) {
        var maybePreprocessInputsChanged = false;
        for (final var stateChange : stateChanges.stateChanges()) {
            if (stateChange.stateId() == STATE_ID_HINTS_KEY_SETS.protoOrdinal()) {
                switch (stateChange.changeOperation().kind()) {
                    case MAP_UPDATE -> {
                        final var key =
                                stateChange.mapUpdateOrThrow().keyOrThrow().hintsPartyIdKeyOrThrow();
                        final var value =
                                stateChange.mapUpdateOrThrow().valueOrThrow().hintsKeySetValueOrThrow();
                        hintsKeySets.put(new PartyKey(key.partyId(), key.numParties()), value);
                        maybePreprocessInputsChanged = true;
                    }
                    case MAP_DELETE -> {
                        final var key =
                                stateChange.mapDeleteOrThrow().keyOrThrow().hintsPartyIdKeyOrThrow();
                        hintsKeySets.remove(new PartyKey(key.partyId(), key.numParties()));
                        maybePreprocessInputsChanged = true;
                    }
                    default -> {
                        // Other operations on this map are not expected to change recomputation inputs.
                    }
                }
            }
        }
        for (final var stateChange : stateChanges.stateChanges()) {
            if (!stateChange.hasSingletonUpdate()) {
                continue;
            }
            final var stateId = stateChange.stateId();
            if (stateId == STATE_ID_LEDGER_ID.protoOrdinal()) {
                captureLedgerId();
            } else if (stateId == STATE_ID_CRS_STATE.protoOrdinal()) {
                captureCrsState();
                maybePreprocessInputsChanged = true;
            } else if (stateId == STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal()
                    || stateId == STATE_ID_NEXT_HINTS_CONSTRUCTION.protoOrdinal()) {
                maybePreprocessInputsChanged |= captureConstructionSnapshot(stateId);
            }
        }
        if (maybePreprocessInputsChanged) {
            printPreprocessDiagnostics();
        }
    }

    private void captureLedgerId() {
        final var rawLedgerId = requireNonNull(
                state.getSingleton(STATE_ID_LEDGER_ID.protoOrdinal()), "Ledger id singleton update did not apply");
        try {
            final var ledgerId = ProtoBytes.PROTOBUF.parse(rawLedgerId).value();
            if (!ledgerId.equals(Bytes.EMPTY)) {
                ledgerIdFromState = ledgerId;
            }
        } catch (ParseException e) {
            throw new IllegalStateException("Failed to parse ledger id singleton value", e);
        }
    }

    private void captureCrsState() {
        final var rawCrsState = requireNonNull(
                state.getSingleton(STATE_ID_CRS_STATE.protoOrdinal()), "CRS state singleton update did not apply");
        try {
            final var crsState = CRSState.PROTOBUF.parse(rawCrsState);
            final var crs = crsState.crs();
            if (Bytes.EMPTY.equals(crs)) {
                return;
            }
            final var previous = currentCrs;
            currentCrs = crs;
            if (!crs.equals(previous)) {
                System.out.printf("  -> hinTS CRS now crsHash=%s (stage=%s)%n", shortSha384Hash(crs), crsState.stage());
            }
        } catch (ParseException e) {
            throw new IllegalStateException("Failed to parse CRS state singleton value", e);
        }
    }

    private boolean captureConstructionSnapshot(final int stateId) {
        final var rawConstruction =
                requireNonNull(state.getSingleton(stateId), "Hints construction singleton update did not apply");
        try {
            final var construction = HintsConstruction.PROTOBUF.parse(rawConstruction);
            if (!construction.hasHintsScheme()) {
                return false;
            }
            final var scheme = construction.hintsSchemeOrThrow();
            final var keys = scheme.preprocessedKeysOrThrow();
            final Map<Long, Integer> partyIds = new HashMap<>();
            final Map<Long, Long> nodeWeights = new HashMap<>();
            long totalWeight = 0L;
            for (final var nodePartyId : scheme.nodePartyIds()) {
                partyIds.put(nodePartyId.nodeId(), nodePartyId.partyId());
                nodeWeights.put(nodePartyId.nodeId(), nodePartyId.partyWeight());
                totalWeight += nodePartyId.partyWeight();
            }
            final var snapshot = new ConstructionSnapshot(
                    construction.constructionId(),
                    keys.aggregationKey(),
                    keys.verificationKey(),
                    Map.copyOf(partyIds),
                    Map.copyOf(nodeWeights),
                    totalWeight);
            final var previous = constructionSnapshots.put(construction.constructionId(), snapshot);
            if (previous == null) {
                System.out.printf(
                        "  -> learned %s hinTS constructionId=%d vkHash=%s, akHash=%s, crsHash=%s, "
                                + "totalWeight=%d, parties=%s%n",
                        hintsConstructionStateName(stateId),
                        construction.constructionId(),
                        shortSha384Hash(snapshot.verificationKey()),
                        shortSha384Hash(snapshot.aggregationKey()),
                        crsHash(),
                        snapshot.totalWeight(),
                        partySummary(snapshot));
                return true;
            } else if (!previous.verificationKey().equals(snapshot.verificationKey())
                    || !previous.aggregationKey().equals(snapshot.aggregationKey())
                    || !previous.partyIds().equals(snapshot.partyIds())
                    || !previous.nodeWeights().equals(snapshot.nodeWeights())) {
                System.out.printf(
                        "  -> %s hinTS constructionId=%d snapshot changed "
                                + "(vkHash %s -> %s, akHash %s -> %s, crsHash=%s)%n",
                        hintsConstructionStateName(stateId),
                        construction.constructionId(),
                        shortSha384Hash(previous.verificationKey()),
                        shortSha384Hash(snapshot.verificationKey()),
                        shortSha384Hash(previous.aggregationKey()),
                        shortSha384Hash(snapshot.aggregationKey()),
                        crsHash());
                return true;
            }
            return false;
        } catch (ParseException e) {
            throw new IllegalStateException("Failed to parse hints construction singleton value", e);
        }
    }

    private void printPreprocessDiagnostics() {
        constructionSnapshots.values().stream()
                .sorted(comparingLong(ConstructionSnapshot::constructionId))
                .forEach(this::printPreprocessDiagnostic);
    }

    private void printPreprocessDiagnostic(@NonNull final ConstructionSnapshot snapshot) {
        final var diagnostic = preprocessDiagnosticFor(snapshot);
        if (!diagnostic.equals(lastPreprocessDiagnostic.put(snapshot.constructionId(), diagnostic))) {
            System.out.println(diagnostic);
        }
    }

    private String preprocessDiagnosticFor(@NonNull final ConstructionSnapshot snapshot) {
        final var crs = currentCrs();
        if (crs == null) {
            return "  -> construction #%d preprocess check unavailable (missing CRS)"
                    .formatted(snapshot.constructionId());
        }
        final var candidate = candidateFor(snapshot);
        if (candidate == null) {
            return ("  -> construction #%d preprocess check unavailable "
                            + "(crsHash=%s, knownPartySizes=%s, expectedParties=%s, keySetMismatches=%s)")
                    .formatted(
                            snapshot.constructionId(),
                            crsHash(),
                            knownPartySizes(),
                            expectedPartySummary(snapshot),
                            keySetMismatchSummary(snapshot));
        }
        useHintsBridgeFor(snapshot.constructionId());
        final var output =
                hintsLibrary.preprocess(crs, candidate.hintKeys(), candidate.weights(), candidate.numParties());
        if (output == null) {
            return ("  -> construction #%d preprocess check failed "
                            + "(n=%d, crsHash=%s, hints=%s, weights=%s, library returned null)")
                    .formatted(
                            snapshot.constructionId(),
                            candidate.numParties(),
                            crsHash(),
                            hintKeySummary(candidate),
                            candidate.weights());
        }
        final var recomputedAk = Bytes.wrap(output.aggregationKey());
        final var recomputedVk = Bytes.wrap(output.verificationKey());
        return ("  -> construction #%d preprocess check "
                        + "(n=%d, crsHash=%s, hints=%s, weights=%s): "
                        + "vkMatches=%s (stored=%s, recomputed=%s), akMatches=%s (stored=%s, recomputed=%s)")
                .formatted(
                        snapshot.constructionId(),
                        candidate.numParties(),
                        crsHash(),
                        hintKeySummary(candidate),
                        candidate.weights(),
                        snapshot.verificationKey().equals(recomputedVk) ? "yes" : "no",
                        shortSha384Hash(snapshot.verificationKey()),
                        shortSha384Hash(recomputedVk),
                        snapshot.aggregationKey().equals(recomputedAk) ? "yes" : "no",
                        shortSha384Hash(snapshot.aggregationKey()),
                        shortSha384Hash(recomputedAk));
    }

    private @Nullable PreprocessCandidate candidateFor(@NonNull final ConstructionSnapshot snapshot) {
        return knownPartySizes().stream()
                .filter(numParties -> hasUsableKeySetsFor(snapshot, numParties))
                .map(numParties -> candidateFor(snapshot, numParties))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private @Nullable PreprocessCandidate candidateFor(
            @NonNull final ConstructionSnapshot snapshot, final int numParties) {
        final TreeMap<Integer, Bytes> hintKeys = new TreeMap<>();
        final TreeMap<Integer, Long> weights = new TreeMap<>();
        for (final var entry : snapshot.partyIds().entrySet()) {
            final var nodeId = entry.getKey();
            final var partyId = entry.getValue();
            final var keySet = hintsKeySets.get(new PartyKey(partyId, numParties));
            if (keySet == null || keySet.nodeId() != nodeId || keySet.key().length() == 0) {
                return null;
            }
            hintKeys.put(partyId, keySet.key());
            weights.put(partyId, snapshot.nodeWeights().getOrDefault(nodeId, 0L));
        }
        return new PreprocessCandidate(numParties, hintKeys, weights);
    }

    private boolean hasUsableKeySetsFor(@NonNull final ConstructionSnapshot snapshot, final int numParties) {
        for (final var entry : snapshot.partyIds().entrySet()) {
            final var keySet = hintsKeySets.get(new PartyKey(entry.getValue(), numParties));
            if (keySet == null
                    || keySet.nodeId() != entry.getKey()
                    || keySet.key().length() == 0) {
                return false;
            }
        }
        return true;
    }

    private Set<Integer> knownPartySizes() {
        return hintsKeySets.keySet().stream()
                .map(PartyKey::numParties)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private String expectedPartySummary(@NonNull final ConstructionSnapshot snapshot) {
        return snapshot.partyIds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> "node%d=party%d".formatted(entry.getKey(), entry.getValue()))
                .toList()
                .toString();
    }

    private String keySetMismatchSummary(@NonNull final ConstructionSnapshot snapshot) {
        final var summaries = new ArrayList<String>();
        for (final var numParties : knownPartySizes()) {
            final var missingOrMismatched = new ArrayList<String>();
            snapshot.partyIds().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        final var keySet = hintsKeySets.get(new PartyKey(entry.getValue(), numParties));
                        if (keySet == null) {
                            missingOrMismatched.add("party%d=missing".formatted(entry.getValue()));
                        } else if (keySet.nodeId() != entry.getKey()) {
                            missingOrMismatched.add("party%d=node%d not node%d"
                                    .formatted(entry.getValue(), keySet.nodeId(), entry.getKey()));
                        } else if (keySet.key().length() == 0) {
                            missingOrMismatched.add("party%d=emptyKey".formatted(entry.getValue()));
                        }
                    });
            if (!missingOrMismatched.isEmpty()) {
                summaries.add("n=%d:%s".formatted(numParties, missingOrMismatched));
            }
        }
        return summaries.isEmpty() ? "none" : summaries.toString();
    }

    private static String hintKeySummary(@NonNull final PreprocessCandidate candidate) {
        final var summary = new ArrayList<String>();
        candidate
                .hintKeys()
                .forEach((partyId, key) -> summary.add("party%d/keyHash=%s".formatted(partyId, shortSha384Hash(key))));
        return summary.toString();
    }

    private record PreprocessCandidate(
            int numParties,
            @NonNull TreeMap<Integer, Bytes> hintKeys,
            @NonNull TreeMap<Integer, Long> weights) {
        private PreprocessCandidate {
            requireNonNull(hintKeys);
            requireNonNull(weights);
        }
    }

    private String constructionVkHashFor(final long constructionId) {
        final var snapshot = constructionSnapshots.get(constructionId);
        return snapshot == null ? "?" : shortSha384Hash(snapshot.verificationKey());
    }

    private void printOfflineAggregateDiagnosticIfReady(@NonNull final Bytes blockHash, final long constructionId) {
        final var proof = proofSignatures.get(blockHash);
        if (proof == null) {
            return;
        }
        final var byConstruction = observedPartials.get(blockHash);
        if (byConstruction == null) {
            return;
        }
        final var partials = byConstruction.get(constructionId);
        if (partials == null || partials.byNode.isEmpty() || partials.lastDiagnosticSize == partials.byNode.size()) {
            return;
        }
        partials.lastDiagnosticSize = partials.byNode.size();
        final var snapshot = constructionSnapshots.get(constructionId);
        if (snapshot == null) {
            System.out.printf(
                    "  -> #%d offline reaggregate unavailable "
                            + "(constructionId=%d, observedPartials=%d, missing construction snapshot)%n",
                    proof.blockNumber(), constructionId, partials.byNode.size());
            return;
        }
        final var crs = currentCrs();
        if (crs == null) {
            System.out.printf(
                    "  -> #%d offline reaggregate unavailable "
                            + "(constructionId=%d, observedPartials=%d, missing CRS)%n",
                    proof.blockNumber(), constructionId, partials.byNode.size());
            return;
        }

        final var validPartials = validPartialsByParty(partials, snapshot);
        final var validWeight = validPartials.values().stream()
                .mapToLong(partial -> snapshot.nodeWeights().getOrDefault(partial.nodeId(), 0L))
                .sum();
        final var thresholdWeight = snapshot.totalWeight() / hintsThresholdDenominator;
        final var thresholdReached = validWeight > thresholdWeight;
        final var invalidCount = partials.byNode.values().stream()
                .filter(partial -> Boolean.FALSE.equals(partial.validation().valid()))
                .count();
        if (!thresholdReached) {
            System.out.printf(
                    "  -> #%d offline reaggregate waiting "
                            + "(constructionId=%d, crsHash=%s, akHash=%s, validPartials=%d/%d, "
                            + "invalidPartials=%d, weight=%d/%d, threshold=>%d)%n",
                    proof.blockNumber(),
                    constructionId,
                    crsHash(),
                    shortSha384Hash(snapshot.aggregationKey()),
                    validPartials.size(),
                    partials.byNode.size(),
                    invalidCount,
                    validWeight,
                    snapshot.totalWeight(),
                    thresholdWeight);
            return;
        }

        final var aggregate = aggregate(validPartials, snapshot, crs);
        final var aggregateValid = aggregate != null
                && hintsLibrary.verifyAggregate(
                        aggregate, blockHash, snapshot.verificationKey(), 1, hintsThresholdDenominator);
        final var aggregateMatchesProof = aggregate != null && aggregate.equals(proof.aggregateSignature());
        final var proofSubset = matchingProofSubset(validPartials, snapshot, crs, proof);
        System.out.printf(
                "  -> #%d offline reaggregate "
                        + "(constructionId=%d, crsHash=%s, akHash=%s, proofVkMatchesConstruction=%s, "
                        + "validPartials=%d/%d, invalidPartials=%d, nodes=%s, weight=%d/%d, threshold=>%d): "
                        + "aggregateValid=%s, aggregateSigHash=%s, proofSigHash=%s, matchesProof=%s, proofSubset=%s%n",
                proof.blockNumber(),
                constructionId,
                crsHash(),
                shortSha384Hash(snapshot.aggregationKey()),
                proof.verificationKey().equals(snapshot.verificationKey()) ? "yes" : "no",
                validPartials.size(),
                partials.byNode.size(),
                invalidCount,
                nodeSummary(validPartials),
                validWeight,
                snapshot.totalWeight(),
                thresholdWeight,
                aggregateValid ? "yes" : "no",
                aggregate == null ? "null" : shortSha384Hash(aggregate),
                shortSha384Hash(proof.aggregateSignature()),
                aggregateMatchesProof ? "yes" : "no",
                proofSubset);
        if (DUMP_INVALID_AGGREGATE_VECTORS
                && !aggregateValid
                && aggregate != null
                && validWeight == snapshot.totalWeight()) {
            dumpInvalidAggregateVector(proof, constructionId, snapshot, crs, validPartials, aggregate);
        }
    }

    private void dumpInvalidAggregateVector(
            @NonNull final ProofSignature proof,
            final long constructionId,
            @NonNull final ConstructionSnapshot snapshot,
            @NonNull final Bytes crs,
            @NonNull final Map<Integer, ObservedPartial> validPartials,
            @NonNull final Bytes aggregate) {
        final var dumpKey = "%d:%d".formatted(proof.blockNumber(), constructionId);
        if (!dumpedInvalidAggregateVectors.add(dumpKey)) {
            return;
        }
        System.out.printf(
                "BEGIN_HINTS_INVALID_AGGREGATE_VECTOR block=%d constructionId=%d%n",
                proof.blockNumber(), constructionId);
        System.out.printf("thresholdNumerator=1%n");
        System.out.printf("thresholdDenominator=%d%n", hintsThresholdDenominator);
        System.out.printf("crsHex=%s%n", crs.toHex());
        System.out.printf("aggregationKeyHex=%s%n", snapshot.aggregationKey().toHex());
        System.out.printf("verificationKeyHex=%s%n", snapshot.verificationKey().toHex());
        System.out.printf("blockHashHex=%s%n", proof.blockHash().toHex());
        System.out.printf("aggregateHex=%s%n", aggregate.toHex());
        System.out.printf("proofAggregateHex=%s%n", proof.aggregateSignature().toHex());
        System.out.printf(
                "parties=%s%n",
                validPartials.keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        validPartials.forEach((partyId, partial) -> System.out.printf(
                "partialSignatureParty%dHex=%s%n", partyId, partial.signature().toHex()));
        System.out.printf(
                "END_HINTS_INVALID_AGGREGATE_VECTOR block=%d constructionId=%d%n", proof.blockNumber(), constructionId);
    }

    private Map<Integer, ObservedPartial> validPartialsByParty(
            @NonNull final ObservedPartials partials, @NonNull final ConstructionSnapshot snapshot) {
        final Map<Integer, ObservedPartial> validPartials = new TreeMap<>();
        partials.byNode.values().stream()
                .filter(partial -> Boolean.TRUE.equals(partial.validation().valid()))
                .sorted((a, b) -> Long.compare(a.nodeId(), b.nodeId()))
                .forEach(partial -> {
                    final var partyId = snapshot.partyIds().get(partial.nodeId());
                    if (partyId != null) {
                        validPartials.putIfAbsent(partyId, partial);
                    }
                });
        return validPartials;
    }

    private @Nullable Bytes aggregate(
            @NonNull final Map<Integer, ObservedPartial> validPartials,
            @NonNull final ConstructionSnapshot snapshot,
            @NonNull final Bytes crs) {
        if (validPartials.isEmpty()) {
            return null;
        }
        final Map<Integer, Bytes> signatures = new TreeMap<>();
        validPartials.forEach((partyId, partial) -> signatures.put(partyId, partial.signature()));
        useHintsBridgeFor(snapshot.constructionId());
        return hintsLibrary.aggregateSignatures(crs, snapshot.aggregationKey(), snapshot.verificationKey(), signatures);
    }

    private void useHintsBridgeFor(final long constructionId) {
        if (lastHintsBridgeConstructionId != constructionId) {
            HintsLibraryBridge.getInstance().resetCache();
            lastHintsBridgeConstructionId = constructionId;
        }
    }

    private String matchingProofSubset(
            @NonNull final Map<Integer, ObservedPartial> validPartials,
            @NonNull final ConstructionSnapshot snapshot,
            @NonNull final Bytes crs,
            @NonNull final ProofSignature proof) {
        final var n = validPartials.size();
        if (n == 0) {
            return "none";
        }
        if (n > MAX_SUBSET_SEARCH_PARTIALS) {
            return "not searched (%d valid partials)".formatted(n);
        }
        final var entries = new ArrayList<>(validPartials.entrySet());
        final var thresholdWeight = snapshot.totalWeight() / hintsThresholdDenominator;
        final var maxMask = 1 << n;
        for (int mask = 1; mask < maxMask; mask++) {
            long weight = 0L;
            final Map<Integer, Bytes> signatures = new TreeMap<>();
            final List<Long> nodes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    final var entry = entries.get(i);
                    final var partial = entry.getValue();
                    weight += snapshot.nodeWeights().getOrDefault(partial.nodeId(), 0L);
                    signatures.put(entry.getKey(), partial.signature());
                    nodes.add(partial.nodeId());
                }
            }
            if (weight <= thresholdWeight) {
                continue;
            }
            useHintsBridgeFor(snapshot.constructionId());
            final var aggregate = hintsLibrary.aggregateSignatures(
                    crs, snapshot.aggregationKey(), snapshot.verificationKey(), signatures);
            if (proof.aggregateSignature().equals(aggregate)) {
                return "nodes=%s, weight=%d".formatted(nodeList(nodes), weight);
            }
        }
        return "none";
    }

    private @Nullable Bytes currentCrs() {
        return currentCrs;
    }

    private String crsHash() {
        final var crs = currentCrs();
        return crs == null ? "?" : shortSha384Hash(crs);
    }

    private static String partySummary(@NonNull final ConstructionSnapshot snapshot) {
        final var summary = new ArrayList<String>();
        snapshot.partyIds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> summary.add("node%d=party%d/w%d"
                        .formatted(
                                entry.getKey(),
                                entry.getValue(),
                                snapshot.nodeWeights().getOrDefault(entry.getKey(), 0L))));
        return summary.toString();
    }

    private static String nodeSummary(@NonNull final Map<Integer, ObservedPartial> validPartials) {
        final var nodes = validPartials.values().stream()
                .map(ObservedPartial::nodeId)
                .sorted()
                .toList();
        return nodeList(nodes);
    }

    private static String nodeList(@NonNull final List<Long> nodes) {
        return nodes.stream().map(nodeId -> "node" + nodeId).toList().toString();
    }

    private static String hintsConstructionStateName(final int stateId) {
        return stateId == STATE_ID_ACTIVE_HINTS_CONSTRUCTION.protoOrdinal() ? "active" : "next";
    }

    private static String shortSha384Hash(@NonNull final Bytes value) {
        return shortHash(noThrowSha384HashOf(value));
    }

    private static String shortHash(@NonNull final Bytes hash) {
        final var asText = hash.toString();
        return asText.substring(0, Math.min(8, asText.length()));
    }

    private static void hashSubTrees(
            @NonNull final BlockItem item,
            @NonNull final IncrementalStreamingHasher inputTreeHasher,
            @NonNull final IncrementalStreamingHasher outputTreeHasher,
            @NonNull final IncrementalStreamingHasher consensusHeaderHasher,
            @NonNull final IncrementalStreamingHasher stateChangesHasher,
            @NonNull final IncrementalStreamingHasher traceDataHasher) {
        final var serialized = BlockItem.PROTOBUF.toBytes(item).toByteArray();

        switch (item.item().kind()) {
            case EVENT_HEADER, ROUND_HEADER -> consensusHeaderHasher.addLeaf(serialized);
            case SIGNED_TRANSACTION -> inputTreeHasher.addLeaf(serialized);
            case TRANSACTION_RESULT, TRANSACTION_OUTPUT, BLOCK_HEADER -> outputTreeHasher.addLeaf(serialized);
            case STATE_CHANGES -> stateChangesHasher.addLeaf(serialized);
            case TRACE_DATA -> traceDataHasher.addLeaf(serialized);
            default -> {
                // Other items are not part of the input/output trees.
            }
        }
    }

    private record RootAndSiblingHashes(Bytes blockRootHash, MerkleSiblingHash[] siblingHashes) {}

    /**
     * Presence for each branch is determined from its hasher's actual leaf count, not by comparing the
     * resulting hash to {@link BlockStreamManager#HASH_OF_ZERO}: a subtree whose only leaf serializes to
     * zero-length content would hash to exactly {@code HASH_OF_ZERO} too (since that sentinel is itself just
     * {@code SHA384(0x00)}), so the hash value alone can't reliably distinguish "no leaves" from "one leaf with
     * empty content".
     */
    private static RootAndSiblingHashes computeBlockHash(
            @NonNull final Timestamp blockTimestamp,
            @NonNull final Bytes previousBlockHash,
            @NonNull final IncrementalStreamingHasher prevBlockRootsHasher,
            @NonNull final Bytes startOfBlockStateHash,
            @NonNull final IncrementalStreamingHasher inputTreeHasher,
            @NonNull final IncrementalStreamingHasher outputTreeHasher,
            @NonNull final IncrementalStreamingHasher consensusHeaderHasher,
            @NonNull final IncrementalStreamingHasher stateChangesHasher,
            @NonNull final IncrementalStreamingHasher traceDataHasher) {
        final var prevBlocksRootHash =
                prevBlockRootsHasher.isEmpty() ? null : Bytes.wrap(prevBlockRootsHasher.computeRootHash());
        final var consensusHeaderHash =
                consensusHeaderHasher.isEmpty() ? null : Bytes.wrap(consensusHeaderHasher.computeRootHash());
        final var inputTreeHash = inputTreeHasher.isEmpty() ? null : Bytes.wrap(inputTreeHasher.computeRootHash());
        final var outputTreeHash = outputTreeHasher.isEmpty() ? null : Bytes.wrap(outputTreeHasher.computeRootHash());
        final var traceDataHash = traceDataHasher.isEmpty() ? null : Bytes.wrap(traceDataHasher.computeRootHash());
        final var stateChangesHash =
                stateChangesHasher.isEmpty() ? null : Bytes.wrap(stateChangesHasher.computeRootHash());

        // Depth5Node1 and depth5Node2 are never absent, since previousBlockHash and startOfBlockStateHash are
        // always present; depth5Node3 and depth5Node4 may each collapse to a single child, or be entirely
        // absent if both of their branches are empty.
        final var depth5Node1 = BlockImplUtils.combineChildren(previousBlockHash, prevBlocksRootHash);
        final var depth5Node2 = BlockImplUtils.combineChildren(startOfBlockStateHash, consensusHeaderHash);
        final var depth5Node3 = BlockImplUtils.combineChildren(inputTreeHash, outputTreeHash);
        final var depth5Node4 = BlockImplUtils.combineChildren(stateChangesHash, traceDataHash);
        final var depth4Node1 = BlockImplUtils.combineChildren(depth5Node1, depth5Node2);
        final var depth4Node2 = BlockImplUtils.combineChildren(depth5Node3, depth5Node4);
        final var depth3Node1 = requireNonNull(BlockImplUtils.combineChildren(depth4Node1, depth4Node2));
        final var depth2Node1 = BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(blockTimestamp));
        final var depth2Node2 = BlockImplUtils.hashInternalNodeSingleChild(depth3Node1);
        final var root = BlockImplUtils.hashInternalNode(depth2Node1, depth2Node2);

        return new RootAndSiblingHashes(root, new MerkleSiblingHash[] {
            new MerkleSiblingHash(false, BlockImplUtils.orEmpty(prevBlocksRootHash)),
            new MerkleSiblingHash(false, requireNonNull(depth5Node2)),
            new MerkleSiblingHash(false, BlockImplUtils.orEmpty(depth4Node2)),
        });
    }

    private static List<Block> readBlocksFrom(@NonNull final Path blockStreamsDir) {
        try (final var paths = Files.walk(blockStreamsDir)) {
            final var blocks = paths.filter(Files::isRegularFile)
                    .filter(path -> candidateBlockNumber(path) >= 0)
                    .map(BlockStreamAccess::blockFrom)
                    .sorted(comparingLong(WrapsFreeBlockSignaturesValidator::blockNumberOf))
                    .toList();
            if (blocks.isEmpty()) {
                throw new IllegalArgumentException("No block files found in " + blockStreamsDir);
            }
            return blocks;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list block files under " + blockStreamsDir, e);
        }
    }

    private static long blockNumberOf(@NonNull final Block block) {
        return block.items().stream()
                .filter(BlockItem::hasBlockHeader)
                .findFirst()
                .map(item -> item.blockHeaderOrThrow().number())
                .orElseThrow(() -> new IllegalArgumentException("Block has no BlockHeader: " + block));
    }

    private static @Nullable Bytes previousBlockHashFrom(@NonNull final Block block) {
        final var footer = footerFrom(block);
        return footer == null ? null : footer.previousBlockRootHash();
    }

    private static @Nullable Bytes persistedBlockHashFrom(@NonNull final Block block) {
        final var blockStreamInfo = BlockStreamAccess.computeSingletonValueFromUpdates(
                List.of(block),
                SingletonUpdateChange::blockStreamInfoValue,
                StateIdentifier.STATE_ID_BLOCK_STREAM_INFO.protoOrdinal());
        if (blockStreamInfo == null || blockStreamInfo.blockNumber() != blockNumberOf(block)) {
            return null;
        }
        return BlockStreamManagerImpl.reconstructLastBlockHash(blockStreamInfo);
    }

    private static @Nullable BlockProof proofFrom(@NonNull final Block block) {
        final var items = block.items();
        return items.isEmpty() || !items.getLast().hasBlockProof()
                ? null
                : items.getLast().blockProofOrThrow();
    }

    private static @Nullable BlockFooter footerFrom(@NonNull final Block block) {
        final var items = block.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).hasBlockFooter()) {
                return items.get(i).blockFooterOrThrow();
            }
        }
        return null;
    }

    private static long candidateBlockNumber(@NonNull final Path path) {
        final var name = path.getFileName().toString();
        if (name.endsWith(".json") || name.endsWith(".mf")) {
            return -1;
        }
        final var standardBlockNumber = BlockStreamAccess.extractBlockNumber(path);
        if (standardBlockNumber >= 0) {
            return standardBlockNumber;
        }
        return pbBlockNumber(name);
    }

    private static long pbBlockNumber(@NonNull final String fileName) {
        var base = fileName;
        if (base.endsWith(".gz")) {
            base = base.substring(0, base.length() - ".gz".length());
        }
        if (!base.endsWith(".pb")) {
            return -1;
        }
        base = base.substring(0, base.length() - ".pb".length());
        var start = base.length();
        while (start > 0 && Character.isDigit(base.charAt(start - 1))) {
            start--;
        }
        if (start == base.length()) {
            return -1;
        }
        return Long.parseLong(base.substring(start));
    }

    private static void printUsage() {
        System.err.println("""
                Usage: WrapsFreeBlockSignaturesValidator <block-stream-dir> [<post-cutover-block-stream-dir> ...]
                                                         <hints-threshold-denominator>

                Each block stream directory may contain standard .blk/.blk.gz files or protobuf .pb files.
                Directories are validated as chronological epochs. At each epoch boundary, state replay continues
                while the block hash context is reinitialized from BlockInfo in the first block of the new epoch.
                """);
    }
}
