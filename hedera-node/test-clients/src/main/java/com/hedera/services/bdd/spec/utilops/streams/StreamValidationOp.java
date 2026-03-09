// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess.BLOCK_STREAM_ACCESS;
import static com.hedera.node.config.types.BlockStreamWriterMode.GRPC;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
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
import com.hedera.node.app.history.impl.ProofControllerImpl;
import com.hedera.services.bdd.junit.support.BlockNodeSubscribeClient;
import com.hedera.services.bdd.junit.support.BlockStreamOutputHelper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

    private final int historyProofsToWaitFor;

    @Nullable
    private final Duration historyProofTimeout;

    public StreamValidationOp(final int historyProofsToWaitFor, @Nullable final Duration historyProofTimeout) {
        this.historyProofsToWaitFor = historyProofsToWaitFor;
        this.historyProofTimeout = historyProofTimeout;
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
                        data -> {
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
                        () -> Assertions.fail("No record stream data found"));

        // If there are no block streams to validate, we are done
        if (spec.startupProperties().getStreamMode("blockStream.streamMode") == RECORDS) {
            return false;
        }
        if (historyProofsToWaitFor > 0) {
            requireNonNull(historyProofTimeout);
            log.info("Waiting up to {} for {} history proofs", historyProofTimeout, historyProofsToWaitFor);
            spec.getNetworkNodes()
                    .forEach(node -> node.minLogsFuture(ProofControllerImpl.PROOF_COMPLETE_MSG, historyProofsToWaitFor)
                            .orTimeout(historyProofTimeout.getSeconds(), TimeUnit.SECONDS)
                            .join());
            // If we waited for more than one history proof, do a freeze
            // upgrade to test adoption of whatever candidate roster
            // triggered production of the last history proof (the first
            // one was the "proof" of the genesis address book)
            if (historyProofsToWaitFor > 1) {
                allRunFor(spec, upgradeToNextConfigVersion());
            }
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
                                            dataRef::set, () -> Assertions.fail("No record stream data found"));
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
                        () -> Assertions.fail("No block streams found.\n" + blockStreamDiagnostics(spec)));
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
        final var writerMode = spec.startupProperties().get("blockStream.writerMode");
        if (GRPC.name().equals(writerMode)) {
            log.info("GRPC writer mode detected, determining freeze pending block boundary from disk artifacts");
            final long freezePendingBlock = freezePendingBlockNumber(spec)
                    .orElseThrow(() -> new AssertionError("No freeze pending block artifact found on disk"));
            log.info("Using freeze pending block #{} as subscribe end_block_number", freezePendingBlock);
            final var subscribeClient = new BlockNodeSubscribeClient();
            final var blockNodeNetwork = HapiSpec.TARGET_BLOCK_NODE_NETWORK.get();
            if (blockNodeNetwork == null) {
                throw new AssertionError("No target block node network available for GRPC stream validation");
            }
            List<Block> blocks = null;
            for (final var blockNodeId : blockNodeNetwork.nodeIds()) {
                try {
                    final int port = spec.getBlockNodePortById(blockNodeId);
                    log.info(
                            "Trying to read blocks from block node {} via subscribe API on localhost:{}",
                            blockNodeId,
                            port);
                    blocks = subscribeClient.fetchBlocks("localhost", port, freezePendingBlock - 1);
                    log.info("Read {} blocks from block node {} subscribe API", blocks.size(), blockNodeId);
                } catch (Exception e) {
                    log.warn("Failed reading blocks via subscribe API from block node {}", blockNodeId, e);
                }
                if (blocks != null && !blocks.isEmpty()) {
                    writeBlocksToOutputDir(spec, blocks);
                    break;
                }
            }
            return Optional.ofNullable(blocks);
        }
        List<Block> blocks = null;
        final var blockPaths = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(BLOCK_STREAMS_DIR))
                .map(Path::toAbsolutePath)
                .toList();
        for (final var path : blockPaths) {
            try {
                log.info("Trying to read blocks from {}", path);
                blocks = BLOCK_STREAM_ACCESS.readBlocks(path);
                log.info("Read {} blocks from {}", blocks.size(), path);
            } catch (Exception ignore) {
                // We will try to read the next node's streams
            }
            if (blocks != null && !blocks.isEmpty()) {
                break;
            }
        }
        return Optional.ofNullable(blocks);
    }

    private static void writeBlocksToOutputDir(@NonNull final HapiSpec spec, @NonNull final List<Block> blocks) {
        try {
            final var blockStreamsDir = BlockStreamOutputHelper.writeBlocksToConfiguredOutput(blocks, null);
            log.info(
                    "Persisted {} GRPC-sourced blocks for '{}' under {}",
                    blocks.size(),
                    spec.getName(),
                    blockStreamsDir.toAbsolutePath());
        } catch (Exception e) {
            throw new AssertionError("Failed persisting GRPC block streams to output directory", e);
        }
    }

    private static OptionalLong freezePendingBlockNumber(@NonNull final HapiSpec spec) {
        log.info("Computing freeze pending block number from .pnd.json artifacts");
        long maxPending = -1L;
        final var blockPaths = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(BLOCK_STREAMS_DIR))
                .map(Path::toAbsolutePath)
                .toList();
        log.info("Inspecting {} block stream paths for pending proof files", blockPaths.size());
        for (final var path : blockPaths) {
            try (final var stream = Files.walk(path)) {
                log.info("Scanning path {} for .pnd.json files", path);
                final long pendingAtPath = stream.map(Path::getFileName)
                        .filter(java.util.Objects::nonNull)
                        .map(Path::toString)
                        .filter(name -> name.endsWith(".pnd.json"))
                        .mapToLong(com.hedera.node.app.hapi.utils.blocks.BlockStreamAccess::extractBlockNumber)
                        .max()
                        .orElse(-1L);
                log.info("Path {} highest pending block candidate = {}", path, pendingAtPath);
                maxPending = Math.max(maxPending, pendingAtPath);
            } catch (Exception e) {
                log.warn("Failed scanning path {} for pending block artifacts", path, e);
            }
        }
        log.info("Final freeze pending block candidate = {}", maxPending);
        return maxPending >= 0 ? OptionalLong.of(maxPending) : OptionalLong.empty();
    }

    private static String blockStreamDiagnostics(@NonNull final HapiSpec spec) {
        final var writerMode = spec.startupProperties().get("blockStream.writerMode");
        final var diagnostics = new StringBuilder("Block stream diagnostics:");
        diagnostics.append("\n  writerMode=").append(writerMode);
        final var blockPaths = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(BLOCK_STREAMS_DIR).toAbsolutePath())
                .toList();
        for (final var path : blockPaths) {
            diagnostics.append("\n  path=").append(path);
            diagnostics.append(", exists=").append(Files.exists(path));
            diagnostics.append(", blkFiles=").append(fileCount(path, "\\.blk(\\.gz)?$"));
            diagnostics.append(", markerFiles=").append(fileCount(path, "\\.mf$"));
            diagnostics.append(", pendingProofFiles=").append(fileCount(path, "\\.pnd\\.json$"));
        }
        if (GRPC.name().equals(writerMode)) {
            final var maybeBlockNodeNetwork = HapiSpec.TARGET_BLOCK_NODE_NETWORK.get();
            diagnostics.append("\n  blockNodeNetworkPresent=").append(maybeBlockNodeNetwork != null);
            if (maybeBlockNodeNetwork != null) {
                final var ids = maybeBlockNodeNetwork.nodeIds();
                diagnostics.append("\n  blockNodeIds=").append(ids);
                for (final var id : ids) {
                    try {
                        diagnostics
                                .append("\n  blockNodePort[")
                                .append(id)
                                .append("]=")
                                .append(spec.getBlockNodePortById(id));
                    } catch (Exception e) {
                        diagnostics
                                .append("\n  blockNodePort[")
                                .append(id)
                                .append("]=<error: ")
                                .append(e.getMessage())
                                .append(">");
                    }
                }
            }
            final var freezePending = freezePendingBlockNumber(spec);
            diagnostics
                    .append("\n  freezePendingBlock=")
                    .append(freezePending.isPresent() ? freezePending.getAsLong() : "<none>");
        }
        return diagnostics.toString();
    }

    private static long fileCount(@NonNull final Path path, @NonNull final String regex) {
        if (!Files.exists(path)) {
            return 0;
        }
        try (final var stream = Files.walk(path)) {
            return stream.map(Path::getFileName)
                    .filter(java.util.Objects::nonNull)
                    .map(Path::toString)
                    .filter(name -> name.matches(regex))
                    .count();
        } catch (Exception e) {
            return -1;
        }
    }

    private static Optional<StreamFileAccess.RecordStreamData> readMaybeRecordStreamDataFor(
            @NonNull final HapiSpec spec) {
        StreamFileAccess.RecordStreamData data = null;
        final var streamLocs = spec.getNetworkNodes().stream()
                .map(node -> node.getExternalPath(RECORD_STREAMS_DIR))
                .map(Path::toAbsolutePath)
                .map(Object::toString)
                .toList();
        for (final var loc : streamLocs) {
            try {
                log.info("Trying to read record files from {}", loc);
                data = STREAM_FILE_ACCESS.readStreamDataFrom(
                        loc, "sidecar", f -> new File(f).length() > MIN_GZIP_SIZE_IN_BYTES);
                log.info("Read {} record files from {}", data.records().size(), loc);
            } catch (Exception ignore) {
                // We will try to read the next node's streams
            }
            if (data != null && !data.records().isEmpty()) {
                break;
            }
        }
        return Optional.ofNullable(data);
    }

    private static void validateProofs(@NonNull final HapiSpec spec) {
        log.info("Beginning block proof validation for each node in the network");
        spec.getNetworkNodes().forEach(node -> {
            try {
                // Get all marker file numbers
                final var path = node.getExternalPath(BLOCK_STREAMS_DIR).toAbsolutePath();
                final var markerFileNumbers = BlockStreamAccess.getAllMarkerFileNumbers(path);

                final var nodeId = node.getNodeId();
                if (markerFileNumbers.isEmpty()) {
                    Assertions.fail(String.format("No marker files found for node %d", nodeId));
                }

                // Get verified block numbers from the simulator
                final var verifiedBlockNumbers = getVerifiedBlockNumbers(spec, nodeId);

                if (verifiedBlockNumbers.isEmpty()) {
                    Assertions.fail(String.format("No verified blocks by block node simulator for node %d", nodeId));
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
