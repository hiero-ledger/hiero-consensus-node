// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_LEDGER_ID;
import static com.hedera.hapi.node.base.HederaFunctionality.LEDGER_ID_PUBLICATION;
import static com.hedera.hapi.util.HapiUtils.asInstant;
import static com.hedera.node.app.blocks.BlockStreamManager.HASH_OF_ZERO;
import static com.hedera.node.app.hapi.utils.CommonUtils.sha384DigestOrThrow;
import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.cryptography.tss.TSS;
import com.hedera.hapi.block.stream.Block;
import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.BlockProof;
import com.hedera.hapi.block.stream.MerkleSiblingHash;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.ServicesMain;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsLibraryImpl;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.translators.inputs.TransactionParts;
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
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.config.PathsConfig;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.junit.jupiter.api.Assertions;

/**
 * Standalone validator for wrap-free TSS block signatures.
 *
 * <p>This intentionally implements only the narrow {@link StateChangesValidator} proof path where WRAPS is disabled:
 * block zero is verified with the TSS convenience API after binary replay finds the ledger id, and later direct block
 * signatures are verified as {@code verificationKey || aggregateSig} with
 * {@link HintsLibrary#verifyAggregate(Bytes, Bytes, Bytes, long, long)}.
 */
@SuppressWarnings("removal")
public class WrapsFreeBlockSignaturesValidator implements BlockStreamValidator {
    private static final Logger logger = LogManager.getLogger(WrapsFreeBlockSignaturesValidator.class);

    private final long hintsThresholdDenominator;
    private final HintsLibrary hintsLibrary;
    private final Metrics metrics;

    private VirtualMapStateImpl state;
    private Instant lastStateChangesTime;
    private StateChanges lastStateChanges;
    private int directProofsVerified;
    private int indirectProofSequencesVerified;
    private int prooflessBlocks;

    @Nullable
    private Bytes ledgerIdFromState;

    @Nullable
    private IndirectProofSequenceValidator indirectProofSeq;

    public static void main(@NonNull final String[] args) {
        final var helpRequested = args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]));
        if (helpRequested || args.length != 2) {
            printUsage();
            if (!helpRequested) {
                System.exit(2);
            }
            return;
        }

        final var blockStreamsDir = Paths.get(args[0]).toAbsolutePath().normalize();
        final var hintsThresholdDenominator = Long.parseLong(args[1]);

        final var blocks = readBlocksFrom(blockStreamsDir);
        final var validator = new WrapsFreeBlockSignaturesValidator(hintsThresholdDenominator);
        validator.validateBlocks(blocks);

        System.out.printf(
                "Verified %d direct block signature(s), %d indirect proof sequence(s); %d block(s) had no proof.%n",
                validator.directProofsVerified, validator.indirectProofSequencesVerified, validator.prooflessBlocks);
    }

    public WrapsFreeBlockSignaturesValidator(final long hintsThresholdDenominator) {
        this.hintsThresholdDenominator = hintsThresholdDenominator;

        metrics = new NoOpMetrics();
        final var platformConfig = ServicesMain.buildPlatformConfig();
        final var pathsConfig = platformConfig.getConfigData(PathsConfig.class);
        final var fileSystemManager = new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        state = new VirtualMapStateImpl(platformConfig, fileSystemManager, metrics);
        this.hintsLibrary = new HintsLibraryImpl();
        logger.info("Initialized wrap-free signature validator with an empty binary state");
    }

    @Override
    public void validateBlocks(@NonNull final List<Block> blocks) {
        requireNonNull(blocks);
        if (blocks.isEmpty()) {
            Assertions.fail("No blocks to validate");
        }

        logger.info("Beginning wrap-free signature validation of {} block(s)", blocks.size());
        var previousBlockHash = HASH_OF_ZERO;
        final var incrementalBlockHashes = new IncrementalStreamingHasher(sha384DigestOrThrow(), List.of(), 0);

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
                } else if (item.hasSignedTransaction()) {
                    final var parts = TransactionParts.from(item.signedTransactionOrThrow());
                    if (parts.function() == LEDGER_ID_PUBLICATION) {
                        final var ledgerIdPublication = parts.body().ledgerIdPublicationOrThrow();
                        final int k = ledgerIdPublication.nodeContributions().size();
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
                logger.warn("Skipping block #{} because it has no footer", blockNumber);
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
            final var proof = proofFrom(block);
            if (proof != null) {
                validateBlockProof(
                        blockNumber,
                        firstBlockRound,
                        footer,
                        proof,
                        expectedBlockHash,
                        startOfStateHash,
                        previousBlockHash,
                        firstConsensusTimestamp,
                        expectedRootAndSiblings.siblingHashes());
            } else {
                prooflessBlocks++;
                logger.warn("Block #{} had no BlockProof", blockNumber);
            }

            previousBlockHash = expectedBlockHash;
            incrementalBlockHashes.addNodeByHash(previousBlockHash.toByteArray());
        }

        if (indirectProofSeq != null && indirectProofSeq.containsIndirectProofs()) {
            Assertions.fail("Cannot verify trailing indirect proof sequence without a following signed block proof");
        }
        logger.info(
                "Validated {} direct signed proof(s), {} indirect proof sequence(s), with {} proofless block(s)",
                directProofsVerified,
                indirectProofSequencesVerified,
                prooflessBlocks);
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
            return;
        } else if (indirectProofSeq != null && indirectProofSeq.containsIndirectProofs()) {
            indirectProofSeq.registerProof(
                    blockNumber, proof, expectedBlockHash, previousBlockHash, blockTimestamp, expectedSiblingHashes);
        }

        verifySignedBlockProof(firstRound, proof, expectedBlockHash);

        if (indirectProofSeq != null && indirectProofSeq.containsIndirectProofs()) {
            logger.info("Verifying contiguous indirect proofs prior to block {}", blockNumber);
            indirectProofSeq.verify();
            indirectProofSeq = null;
            indirectProofSequencesVerified++;
        }
    }

    private void verifySignedBlockProof(
            final long firstRound, @NonNull final BlockProof proof, @NonNull final Bytes expectedBlockHash) {
        final var signature = proof.signedBlockProofOrThrow().blockSignature();
        final boolean valid;
        if (proof.block() > 0) {
            final var vk = signature.slice(0, HintsLibraryImpl.VK_LENGTH);
            final var sig =
                    signature.slice(HintsLibraryImpl.VK_LENGTH, signature.length() - HintsLibraryImpl.VK_LENGTH);
            valid = hintsLibrary.verifyAggregate(sig, expectedBlockHash, vk, 1, hintsThresholdDenominator);
        } else {
            requireNonNull(ledgerIdFromState, "Ledger id not available for block #0 signature verification");
            valid = TSS.verifyTSS(
                    ledgerIdFromState.toByteArray(), signature.toByteArray(), expectedBlockHash.toByteArray());
        }
        if (!valid) {
            Assertions.fail(() -> "Invalid signature in proof (start round #" + firstRound + ") - " + proof);
        }
        directProofsVerified++;
        logger.info("Verified wrap-free signature on block #{}", proof.block());
    }

    private void applyStateChanges(@NonNull final StateChanges stateChanges) {
        BinaryStateChangeParser.applyStateChanges(state, StateChanges.PROTOBUF.toBytes(stateChanges));
        captureLedgerIdFrom(stateChanges);
    }

    private void captureLedgerIdFrom(@NonNull final StateChanges stateChanges) {
        for (final var stateChange : stateChanges.stateChanges()) {
            if (stateChange.stateId() == STATE_ID_LEDGER_ID.protoOrdinal() && stateChange.hasSingletonUpdate()) {
                final var rawLedgerId = requireNonNull(
                        state.getSingleton(STATE_ID_LEDGER_ID.protoOrdinal()),
                        "Ledger id singleton update did not apply");
                try {
                    ledgerIdFromState = ProtoBytes.PROTOBUF.parse(rawLedgerId).value();
                } catch (ParseException e) {
                    throw new IllegalStateException("Failed to parse ledger id singleton value", e);
                }
            }
        }
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

    private static RootAndSiblingHashes computeBlockHash(
            @NonNull final Timestamp blockTimestamp,
            @NonNull final Bytes previousBlockHash,
            @NonNull final IncrementalStreamingHasher prevBlockRootsHasher,
            @NonNull final Bytes startOfBlockStateHash,
            @NonNull final IncrementalStreamingHasher inputTreeHasher,
            @NonNull final IncrementalStreamingHasher outputTreeHasher,
            @NonNull final IncrementalStreamingHasher consensusHeaderHasher,
            @NonNull final Bytes finalStateChangesHash,
            @NonNull final IncrementalStreamingHasher traceDataHasher) {
        final var prevBlocksRootHash = Bytes.wrap(prevBlockRootsHasher.computeRootHash());
        final var consensusHeaderHash = Bytes.wrap(consensusHeaderHasher.computeRootHash());
        final var inputTreeHash = Bytes.wrap(inputTreeHasher.computeRootHash());
        final var outputTreeHash = Bytes.wrap(outputTreeHasher.computeRootHash());
        final var traceDataHash = Bytes.wrap(traceDataHasher.computeRootHash());

        final var depth5Node1 = BlockImplUtils.hashInternalNode(previousBlockHash, prevBlocksRootHash);
        final var depth5Node2 = BlockImplUtils.hashInternalNode(startOfBlockStateHash, consensusHeaderHash);
        final var depth5Node3 = BlockImplUtils.hashInternalNode(inputTreeHash, outputTreeHash);
        final var depth5Node4 = BlockImplUtils.hashInternalNode(finalStateChangesHash, traceDataHash);
        final var depth4Node1 = BlockImplUtils.hashInternalNode(depth5Node1, depth5Node2);
        final var depth4Node2 = BlockImplUtils.hashInternalNode(depth5Node3, depth5Node4);
        final var depth3Node1 = BlockImplUtils.hashInternalNode(depth4Node1, depth4Node2);
        final var depth2Node1 = BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(blockTimestamp));
        final var depth2Node2 = BlockImplUtils.hashInternalNodeSingleChild(depth3Node1);
        final var root = BlockImplUtils.hashInternalNode(depth2Node1, depth2Node2);

        return new RootAndSiblingHashes(root, new MerkleSiblingHash[] {
            new MerkleSiblingHash(false, prevBlocksRootHash),
            new MerkleSiblingHash(false, depth5Node2),
            new MerkleSiblingHash(false, depth4Node2),
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
                Usage: WrapsFreeBlockSignaturesValidator <block-stream-dir> <hints-threshold-denominator>

                The block stream directory may contain standard .blk/.blk.gz files or protobuf .pb files.
                """);
    }
}
