// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_PARENT_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeMode;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeContainer;
import com.hedera.services.bdd.junit.hedera.containers.BlockNodeSubscribeClient;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.junit.support.validators.BalanceReconciliationValidator;
import com.hedera.services.bdd.junit.support.validators.BlockNoValidator;
import com.hedera.services.bdd.junit.support.validators.ExpiryRecordsValidator;
import com.hedera.services.bdd.junit.support.validators.TokenReconciliationValidator;
import com.hedera.services.bdd.junit.support.validators.TransactionBodyValidator;
import com.hedera.services.bdd.junit.support.validators.WrappedRecordHashesByRecordFilesValidator;
import com.hedera.services.bdd.junit.support.validators.block.BlockContentsValidator;
import com.hedera.services.bdd.junit.support.validators.block.BlockNumberSequenceValidator;
import com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator;
import com.hedera.services.bdd.junit.support.validators.block.TransactionRecordParityValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

/**
 * A {@link UtilOp} that validates the streams produced by the target network of the given
 * {@link HapiSpec}. Note it suffices to validate the streams produced by a single node in
 * the network since at minimum log validation will fail in case of an ISS.
 */
public class StreamValidationOp extends UtilOp implements LifecycleTest {
    private static final Logger log = LogManager.getLogger(StreamValidationOp.class);

    private static final long MAX_BLOCK_TIME_MS = 2000L;
    private static final long BUFFER_MS = 500L;
    private static final long MIN_GZIP_SIZE_IN_BYTES = 26;
    private static final String ERROR_PREFIX = "\n  - ";
    private static final Duration STREAM_FILE_WAIT = Duration.ofSeconds(2);

    private final List<RecordStreamValidator> recordStreamValidators;
    private final WrappedRecordHashesByRecordFilesValidator wrappedRecordHashesValidator =
            new WrappedRecordHashesByRecordFilesValidator();

    private static final List<BlockStreamValidator.Factory> BLOCK_STREAM_VALIDATOR_FACTORIES = List.of(
            TransactionRecordParityValidator.FACTORY,
            StateChangesValidator.FACTORY,
            BlockContentsValidator.FACTORY,
            BlockNumberSequenceValidator.FACTORY
            // (FUTURE) Disabled until PCES events are integrated as the source of truth. See GH issue #22769.
            //            EventHashBlockStreamValidator.FACTORY,
            //            RedactingEventHashBlockStreamValidator.FACTORY
            );

    private record DataOrException(
            @Nullable StreamFileAccess.RecordStreamData data,
            @Nullable Exception e) {}

    public StreamValidationOp() {
        this.recordStreamValidators = List.of(
                new BlockNoValidator(),
                new TransactionBodyValidator(),
                new ExpiryRecordsValidator(),
                new BalanceReconciliationValidator(),
                new TokenReconciliationValidator());
    }

    @Override
    protected boolean submitOp(@NonNull final HapiSpec spec) throws Throwable {
        // Prepare streams for record validators that depend on querying the network and hence
        // cannot be run after submitting a freeze
        allRunFor(
                spec,
                // Ensure only top-level txs could change balances before validations
                overridingTwo("nodes.nodeRewardsEnabled", "false", "nodes.feeCollectionAccountEnabled", "false"),
                // Ensure the CryptoTransfer below will be in a new block period
                sleepFor(MAX_BLOCK_TIME_MS + BUFFER_MS),
                cryptoTransfer((ignore, b) -> {}).payingWith(GENESIS),
                // Wait for the final record file to be created
                sleepFor(2 * BUFFER_MS));
        // Validate the record streams
        final AtomicReference<StreamFileAccess.RecordStreamData> dataRef = new AtomicReference<>();
        readMaybeRecordStreamDataFor(spec)
                .ifPresentOrElse(
                        dataOrException -> {
                            final var data = dataOrException.data();
                            if (data == null) {
                                Assertions.fail(
                                        "Unable to read stream data at " + recordStreamLocationsOf(spec),
                                        dataOrException.e());
                            }
                            final var maybeErrors = recordStreamValidators.stream()
                                    .flatMap(v -> v.validationErrorsIn(data))
                                    .peek(t -> log.error("Record stream validation error!", t))
                                    .map(Throwable::getMessage)
                                    .collect(joining(ERROR_PREFIX));
                            if (!maybeErrors.isBlank()) {
                                throw new AssertionError(
                                        "Record stream validation failed:" + ERROR_PREFIX + maybeErrors);
                            }
                            dataRef.set(data);
                        },
                        () -> Assertions.fail(
                                "Aborted reading record stream data at " + recordStreamLocationsOf(spec)));

        // If there are no block streams to validate, we are done
        if (spec.startupProperties().getStreamMode("blockStream.streamMode") == RECORDS) {
            return false;
        }
        // Freeze the network
        allRunFor(
                spec,
                freezeOnly().payingWith(GENESIS).startingIn(2).seconds(),
                spec.targetNetworkType() == SUBPROCESS_NETWORK ? waitForFrozenNetwork(FREEZE_TIMEOUT) : noOp(),
                // Wait for the final stream files to be created
                sleepFor(STREAM_FILE_WAIT.toMillis()));
        readMaybeBlockStreamsFor(spec)
                .ifPresentOrElse(
                        blocks -> {
                            // Re-read the record streams since they may have been updated
                            readMaybeRecordStreamDataFor(spec)
                                    .ifPresentOrElse(
                                            dataOrException -> {
                                                final var data = dataOrException.data();
                                                if (data == null) {
                                                    Assertions.fail(
                                                            "Unable to re-read stream data at "
                                                                    + recordStreamLocationsOf(spec),
                                                            dataOrException.e());
                                                }
                                                dataRef.set(data);
                                            },
                                            () -> Assertions.fail("No record stream data found"));
                            final var data = requireNonNull(dataRef.get());
                            final var maybeErrors = BLOCK_STREAM_VALIDATOR_FACTORIES.stream()
                                    .filter(factory -> factory.appliesTo(spec))
                                    .map(factory -> factory.create(spec))
                                    .flatMap(v -> v.validationErrorsIn(blocks, data))
                                    .peek(t -> log.error("Block stream validation error", t))
                                    .map(Throwable::getMessage)
                                    .collect(joining(ERROR_PREFIX));
                            if (!maybeErrors.isBlank()) {
                                throw new AssertionError(
                                        "Block stream validation failed:" + ERROR_PREFIX + maybeErrors);
                            }
                        },
                        () -> Assertions.fail("No block streams found"));
        validateProofs(spec);

        // CI-focused cross-node validation of wrapped record hashes for nodes with identical record stream files
        final var maybeWrappedHashesErrors = wrappedRecordHashesValidator
                .validationErrorsIn(spec)
                .peek(t -> log.error("Wrapped record hashes validation error!", t))
                .map(Throwable::getMessage)
                .collect(joining(ERROR_PREFIX));
        if (!maybeWrappedHashesErrors.isBlank()) {
            throw new AssertionError(
                    "Wrapped record hashes validation failed:" + ERROR_PREFIX + maybeWrappedHashesErrors);
        }

        return false;
    }

    static Optional<List<Block>> readMaybeBlockStreamsFor(@NonNull final HapiSpec spec) {
        final var blockNodeNetwork = HapiSpec.TARGET_BLOCK_NODE_NETWORK.get();
        if (isWriterModeGrpcOnly(spec) && blockNodeNetwork != null) {
            return readBlocksFromBlockNodes(spec, blockNodeNetwork);
        }
        return readBlocksFromDisk(spec);
    }

    private static Optional<List<Block>> readBlocksFromDisk(@NonNull final HapiSpec spec) {
        final var blockStreamDirs = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(BLOCK_STREAMS_PARENT_DIR))
                .map(Path::toAbsolutePath)
                .distinct()
                .toList();
        // Pick the node directory with the most block files (compare by count first,
        // then sort only the winner to avoid unnecessary sorting of discarded lists)
        Path bestDir = null;
        List<Path> bestPaths = null;
        for (final var dir : blockStreamDirs) {
            try (final var stream = Files.walk(dir)) {
                final var paths = stream.filter(p -> BlockStreamAccess.isBlockFile(p, true))
                        .toList();
                if (bestPaths == null || paths.size() > bestPaths.size()) {
                    bestDir = dir;
                    bestPaths = paths;
                }
            } catch (Exception ignore) {
                // We will try the next node's directory
            }
        }
        if (bestPaths == null || bestPaths.isEmpty()) {
            return Optional.empty();
        }
        bestPaths = bestPaths.stream()
                .sorted(Comparator.comparing(BlockStreamAccess::extractBlockNumber))
                .toList();
        // Parse the winner's block files and pair each block with its source path
        final var otherDirs = new ArrayList<>(blockStreamDirs);
        otherDirs.remove(bestDir);
        final var result = new ArrayList<Block>(bestPaths.size());
        for (final var blockPath : bestPaths) {
            final var relativePath = bestDir.relativize(blockPath);
            Block block;
            try {
                block = BlockStreamAccess.blockFrom(blockPath);
            } catch (Exception e) {
                log.warn("Failed to parse block file {}", blockPath, e);
                // Try the same file from other node directories to avoid a gap
                final var fallback = findCompleteBlockIn(otherDirs, relativePath);
                if (fallback != null) {
                    result.add(fallback);
                }
                continue;
            }
            final var items = block.items();
            if (items.isEmpty() || items.getLast().hasBlockProof()) {
                result.add(block);
                continue;
            }
            // Incomplete block — try the same relative path under other node directories
            final var replacement = findCompleteBlockIn(otherDirs, relativePath);
            result.add(replacement != null ? replacement : block);
        }
        return result.isEmpty() ? Optional.empty() : Optional.of(result);
    }

    /**
     * Tries to find a complete version of a block file by resolving the same relative path
     * under each of the given directories. Returns the first block whose last item is a proof.
     */
    @Nullable
    private static Block findCompleteBlockIn(@NonNull final List<Path> otherDirs, @NonNull final Path relativePath) {
        for (final var dir : otherDirs) {
            final var candidatePath = dir.resolve(relativePath);
            if (!Files.exists(candidatePath)) {
                continue;
            }
            try {
                final var block = BlockStreamAccess.blockFrom(candidatePath);
                final var items = block.items();
                if (!items.isEmpty() && items.getLast().hasBlockProof()) {
                    return block;
                }
            } catch (Exception ignore) {
                // Try the next directory
            }
        }
        return null;
    }

    private static Optional<List<Block>> readBlocksFromBlockNodes(
            @NonNull final HapiSpec spec, @NonNull final BlockNodeNetwork blockNodeNetwork) {
        // Determine the configured block node mode from the first entry
        final var mode = blockNodeNetwork.getBlockNodeModeById().values().stream()
                .findFirst()
                .orElse(BlockNodeMode.NONE);

        List<Block> blocks = null;
        if (mode == BlockNodeMode.SIMULATOR) {
            blocks = readBlocksFromSimulators(blockNodeNetwork);
        } else if (mode == BlockNodeMode.REAL) {
            blocks = readBlocksFromRealContainers(blockNodeNetwork);
        }

        if (blocks == null || blocks.isEmpty()) {
            return Optional.empty();
        }

        // Also read pending blocks (.pnd.gz) from subprocess nodes for the freeze block
        final var pendingBlocks = readPendingBlocksFromDisk(spec);
        if (!pendingBlocks.isEmpty()) {
            final var allBlocks = new ArrayList<>(blocks);
            allBlocks.addAll(pendingBlocks);
            allBlocks.sort(Comparator.comparingLong(block -> block.items().isEmpty()
                    ? Long.MAX_VALUE
                    : block.items().getFirst().hasBlockHeader()
                            ? block.items().getFirst().blockHeader().number()
                            : Long.MAX_VALUE));
            blocks = allBlocks;
            log.info("Added {} pending blocks from disk, total {} blocks", pendingBlocks.size(), blocks.size());
        }

        return Optional.of(blocks);
    }

    @Nullable
    private static List<Block> readBlocksFromSimulators(@NonNull final BlockNodeNetwork blockNodeNetwork) {
        for (final Map.Entry<Long, SimulatedBlockNodeServer> entry :
                blockNodeNetwork.getSimulatedBlockNodeById().entrySet()) {
            try {
                final var blocks = entry.getValue().getAllVerifiedBlocks();
                log.info("Read {} blocks from simulator block node {}", blocks.size(), entry.getKey());
                if (!blocks.isEmpty()) {
                    return blocks;
                }
            } catch (Exception e) {
                log.warn("Failed to read blocks from simulator block node {}", entry.getKey(), e);
            }
        }
        return null;
    }

    @Nullable
    private static List<Block> readBlocksFromRealContainers(@NonNull final BlockNodeNetwork blockNodeNetwork) {
        for (final Map.Entry<Long, BlockNodeContainer> entry :
                blockNodeNetwork.getBlockNodeContainerById().entrySet()) {
            try {
                final var container = entry.getValue();
                try (final var client = new BlockNodeSubscribeClient(container.getHost(), container.getPort())) {
                    final long lastBlock = client.getLastAvailableBlock();
                    if (lastBlock >= 0) {
                        final var blocks = client.subscribeBlocks(0, lastBlock);
                        log.info(
                                "Read {} blocks from real block node {} (blocks 0-{})",
                                blocks.size(),
                                entry.getKey(),
                                lastBlock);
                        if (!blocks.isEmpty()) {
                            return blocks;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to read blocks from real block node {}", entry.getKey(), e);
            }
        }
        return null;
    }

    private static List<Block> readPendingBlocksFromDisk(@NonNull final HapiSpec spec) {
        final var pendingBlocks = new ArrayList<Block>();
        final var blockPaths = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(BLOCK_STREAMS_PARENT_DIR))
                .map(Path::toAbsolutePath)
                .distinct()
                .toList();
        for (final var parentPath : blockPaths) {
            if (!Files.exists(parentPath)) {
                continue;
            }
            try (final var stream = Files.walk(parentPath)) {
                stream.filter(p -> {
                            final var name = p.getFileName().toString();
                            return name.endsWith(".pnd.gz") || name.endsWith(".pnd");
                        })
                        .filter(p -> BlockStreamAccess.isBlockFile(p, false))
                        .forEach(p -> {
                            try {
                                pendingBlocks.add(BlockStreamAccess.blockFrom(p));
                                log.info("Read pending block from {}", p);
                            } catch (Exception e) {
                                log.warn("Failed to read pending block from {}", p, e);
                            }
                        });
            } catch (IOException e) {
                log.warn("Failed to scan for pending blocks in {}", parentPath, e);
            }
            if (!pendingBlocks.isEmpty()) {
                break;
            }
        }
        return pendingBlocks;
    }

    private static boolean isWriterModeGrpcOnly(@NonNull final HapiSpec spec) {
        try {
            final var writerMode = spec.startupProperties().get("blockStream.writerMode");
            return BlockStreamWriterMode.GRPC.name().equals(writerMode);
        } catch (Exception e) {
            return false;
        }
    }

    private static Optional<DataOrException> readMaybeRecordStreamDataFor(@NonNull final HapiSpec spec) {
        Exception lastException = null;
        StreamFileAccess.RecordStreamData data = null;
        final var streamLocs = recordStreamLocationsOf(spec);
        for (final var loc : streamLocs) {
            try {
                log.info("Trying to read record files from {}", loc);
                data = STREAM_FILE_ACCESS.readStreamDataFrom(
                        loc,
                        "sidecar",
                        f -> new File(f).length() > MIN_GZIP_SIZE_IN_BYTES,
                        // Record stream files are continually created for gossiping partial signatures when hinTS is
                        // enabled, even without user transactions submitted; so we ignore EOF exceptions here
                        spec.startupProperties().getBoolean("tss.hintsEnabled"));
                log.info("Read {} record files from {}", data.records().size(), loc);
            } catch (Exception e) {
                lastException = e;
            }
            if (data != null && !data.records().isEmpty()) {
                lastException = null;
                break;
            }
        }
        return Optional.of(new DataOrException(data, lastException));
    }

    private static List<String> recordStreamLocationsOf(@NonNull final HapiSpec spec) {
        return spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(RECORD_STREAMS_DIR))
                .map(Path::toAbsolutePath)
                .map(Object::toString)
                .toList();
    }

    private static void validateProofs(@NonNull final HapiSpec spec) {
        if (!isWriterModeGrpcOnly(spec)) {
            log.info("Beginning block proof validation for each node in the network");
            spec.getNetworkNodes().forEach(node -> {
                try {
                    final var path =
                            node.getExternalPath(BLOCK_STREAMS_PARENT_DIR).toAbsolutePath();
                    final var markerFileNumbers = BlockStreamAccess.getAllMarkerFileNumbers(path);

                    final var nodeId = node.getNodeId();
                    if (markerFileNumbers.isEmpty()) {
                        Assertions.fail(String.format("No marker files found for node %d", nodeId));
                    }

                    // Get verified block numbers from the simulator
                    final var verifiedBlockNumbers = getVerifiedBlockNumbers(spec, nodeId);

                    if (verifiedBlockNumbers.isEmpty()) {
                        Assertions.fail(
                                String.format("No verified blocks by block node simulator for node %d", nodeId));
                    }

                    for (final var markerFile : markerFileNumbers) {
                        if (!verifiedBlockNumbers.contains(markerFile)) {
                            Assertions.fail(String.format(
                                    "Marker file for block {%d} on node %d is not verified by the respective block node simulator",
                                    markerFile, nodeId));
                        }
                    }
                    log.info("Successfully validated {} marker files for node {}", markerFileNumbers.size(), nodeId);
                } catch (Exception ignore) {
                    // We will try to read the next node's streams
                }
            });
            log.info("Block proofs validation completed successfully");
        }
    }

    private static Set<Long> getVerifiedBlockNumbers(@NonNull final HapiSpec spec, final long nodeId) {
        final var simulatedBlockNode = spec.getSimulatedBlockNodeById(nodeId);

        if (simulatedBlockNode.hasEverBeenShutdown()) {
            // Check whether other simulated block nodes have verified this block
            return spec.getBlockNodeNetworkIds().stream()
                    .filter(blockNodeId -> blockNodeId != nodeId)
                    .map(blockNodeId ->
                            spec.getSimulatedBlockNodeById(blockNodeId).getReceivedBlockNumbers())
                    .reduce(new HashSet<>(), (acc, blockNumbers) -> {
                        acc.addAll(blockNumbers);
                        acc.addAll(simulatedBlockNode.getReceivedBlockNumbers());
                        return acc;
                    });
        } else {
            return simulatedBlockNode.getReceivedBlockNumbers();
        }
    }
}
