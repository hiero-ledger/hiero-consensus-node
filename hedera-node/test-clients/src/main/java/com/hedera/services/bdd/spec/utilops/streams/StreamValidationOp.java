// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.streams;

import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.BLOCK_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.RECORD_STREAMS_DIR;
import static com.hedera.services.bdd.junit.hedera.ExternalPath.WORKING_DIR;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.support.BlockStreamAccess.BLOCK_STREAM_ACCESS;
import static com.hedera.services.bdd.junit.support.StreamFileAccess.STREAM_FILE_ACCESS;
import static com.hedera.services.bdd.spec.TargetNetworkType.SUBPROCESS_NETWORK;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.noOp;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForFrozenNetwork;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

import com.hedera.hapi.block.stream.Block;
import com.hedera.node.app.history.impl.ProofControllerImpl;
import com.hedera.services.bdd.junit.extensions.NetworkTargetingExtension;
import com.hedera.services.bdd.junit.hedera.BlockNodeNetwork;
import com.hedera.services.bdd.junit.hedera.simulator.SimulatedBlockNodeServer;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNetwork;
import com.hedera.services.bdd.junit.support.BlockStreamAccess;
import com.hedera.services.bdd.junit.support.BlockStreamValidator;
import com.hedera.services.bdd.junit.support.RecordStreamValidator;
import com.hedera.services.bdd.junit.support.StreamFileAccess;
import com.hedera.services.bdd.junit.support.validators.BalanceReconciliationValidator;
import com.hedera.services.bdd.junit.support.validators.BlockNoValidator;
import com.hedera.services.bdd.junit.support.validators.ExpiryRecordsValidator;
import com.hedera.services.bdd.junit.support.validators.TokenReconciliationValidator;
import com.hedera.services.bdd.junit.support.validators.TransactionBodyValidator;
import com.hedera.services.bdd.junit.support.validators.block.BlockContentsValidator;
import com.hedera.services.bdd.junit.support.validators.block.BlockNumberSequenceValidator;
import com.hedera.services.bdd.junit.support.validators.block.StateChangesValidator;
import com.hedera.services.bdd.junit.support.validators.block.TransactionRecordParityValidator;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.utilops.UtilOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
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

    private static final List<BlockStreamValidator.Factory> BLOCK_STREAM_VALIDATOR_FACTORIES = List.of(
            TransactionRecordParityValidator.FACTORY,
            StateChangesValidator.FACTORY,
            BlockContentsValidator.FACTORY,
            BlockNumberSequenceValidator.FACTORY);

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
                overriding("nodes.nodeRewardsEnabled", "false"),
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
        final var diskBlocks = readMaybeBlockStreamsFor(spec).orElse(List.of());
        boolean validatedAny = false;

        // Re-read the record streams since they may have been updated
        readMaybeRecordStreamDataFor(spec)
                .ifPresentOrElse(dataRef::set, () -> Assertions.fail("No record stream data found"));
        final var data = requireNonNull(dataRef.get());

        if (spec.targetNetworkType() == SUBPROCESS_NETWORK) {
            log.info("Waiting for simulator block processing to complete...");
            sleepFor(20000);
        }
        List<Block> simulatorBlocks = readMaybeSimulatorBlocks(spec);

        // If there are on-disk blocks and simulator blocks, let's compare them byte for byte
        if (!diskBlocks.isEmpty() && !simulatorBlocks.isEmpty()) {
            for (int i = 0; i < Math.min(simulatorBlocks.size(), diskBlocks.size()); i++) {
                final var diskBlock = diskBlocks.get(i);
                final var simBlock = simulatorBlocks.get(i);
                if (!diskBlock.equals(simBlock)) {
                    throw new AssertionError(String.format(
                            "Block stream mismatch at index %d: disk block %s, simulator block %s",
                            i, diskBlock, simBlock));
                }
            }
        }

        if (!diskBlocks.isEmpty()) {
            final var maybeErrors = BLOCK_STREAM_VALIDATOR_FACTORIES.stream()
                    .filter(factory -> factory.appliesTo(spec))
                    .map(factory -> factory.create(spec))
                    .flatMap(v -> v.validationErrorsIn(diskBlocks, data))
                    .peek(t -> log.error("Block stream validation error (disk)", t))
                    .map(Throwable::getMessage)
                    .collect(joining(ERROR_PREFIX));
            if (!maybeErrors.isBlank()) {
                throw new AssertionError("(Disk) Block stream validation failed:" + ERROR_PREFIX + maybeErrors);
            }
            validatedAny = true;
        }

        if (!simulatorBlocks.isEmpty()) {
            writeSimulatorBlocksToDisk(spec, simulatorBlocks);
            final var maybeErrors = BLOCK_STREAM_VALIDATOR_FACTORIES.stream()
                    .filter(factory -> factory.appliesTo(spec))
                    .map(factory -> factory.create(spec))
                    .flatMap(v -> v.validationErrorsIn(simulatorBlocks, data))
                    .peek(t -> log.error("Block stream validation error (simulator)", t))
                    .map(Throwable::getMessage)
                    .collect(joining(ERROR_PREFIX));
            if (!maybeErrors.isBlank()) {
                throw new AssertionError("(Simulator) Block stream validation failed:" + ERROR_PREFIX + maybeErrors);
            }
            validatedAny = true;
        }

        if (!validatedAny) {
            Assertions.fail("No block streams found");
        }
        validateSimulatorProofReceipts(spec);

        return false;
    }

    static Optional<List<Block>> readMaybeBlockStreamsFor(@NonNull final HapiSpec spec) {
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

    private static List<Block> readMaybeSimulatorBlocks(@NonNull final HapiSpec spec) {
        final BlockNodeNetwork blockNodeNetwork = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        if (blockNodeNetwork == null) {
            return List.of();
        }
        // Any simulator exists
        return blockNodeNetwork.getSimulatedBlockNodeById().values().stream()
                .findFirst()
                .map(sim -> {
                    final var blocks = sim.getCapturedBlocks();
                    log.info("Read {} blocks from simulator", blocks.size());
                    return blocks;
                })
                .orElse(List.of());
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

    private static void writeSimulatorBlocksToDisk(@NonNull final HapiSpec spec, @NonNull final List<Block> blocks) {
        try {
            if (!(spec.targetNetworkOrThrow() instanceof SubProcessNetwork subProcessNetwork)) {
                return;
            }
            final var node0 = subProcessNetwork.getRequiredNode(byNodeId(0));
            final var outDir =
                    node0.getExternalPath(WORKING_DIR).resolve("data").resolve("simulatorBlockStreams");
            Files.createDirectories(outDir);

            for (final var block : blocks) {
                if (block.items().isEmpty() || !block.items().getFirst().hasBlockHeader()) {
                    continue;
                }
                final long blockNumber =
                        block.items().getFirst().blockHeaderOrThrow().number();
                final var fileName = String.format("%036d.blk.gz", blockNumber);
                final var filePath = outDir.resolve(fileName);

                OutputStream out = null;
                try {
                    out = Files.newOutputStream(filePath);
                    out = new BufferedOutputStream(out, 1024 * 1024);
                    out = new GZIPOutputStream(out, 1024 * 256);
                    out = new BufferedOutputStream(out, 1024 * 1024 * 4);
                    final var bytes = com.hedera.hapi.block.stream.Block.PROTOBUF
                            .toBytes(block)
                            .toByteArray();
                    out.write(bytes);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                } finally {
                    if (out != null) {
                        try {
                            out.close();
                        } catch (final IOException ignore) {
                        }
                    }
                }
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Validates, when a block node simulator is active, that the simulator reports having
     * received a proof for every block produced by the consensus node (as evidenced by
     * the presence of a corresponding on-disk marker file). If no simulator is active,
     * this validation is skipped.
     */
    private static void validateSimulatorProofReceipts(@NonNull final HapiSpec spec) {
        final var blockNodeNetwork = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        if (blockNodeNetwork == null
                || blockNodeNetwork.getSimulatedBlockNodeById().isEmpty()) {
            log.info("Skipping block proof validation: no block node simulator active");
            return;
        }
        log.info("Beginning block proof validation for each node in the network");
        final var verifiedBlockNumbersAll = getAllVerifiedBlockNumbers(spec);
        spec.getNetworkNodes().forEach(node -> {
            try {
                // Get all marker file numbers
                final var path = node.getExternalPath(BLOCK_STREAMS_DIR).toAbsolutePath();
                final var markerFileNumbers = BlockStreamAccess.getAllMarkerFileNumbers(path);

                final var nodeId = node.getNodeId();
                if (markerFileNumbers.isEmpty()) {
                    Assertions.fail(String.format("No marker files found for node %d", nodeId));
                }

                if (verifiedBlockNumbersAll.isEmpty()) {
                    Assertions.fail(String.format("No verified blocks by block node simulator for node %d", nodeId));
                }

                for (final var markerFile : markerFileNumbers) {
                    if (!verifiedBlockNumbersAll.contains(markerFile)) {
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

    private static Set<Long> getAllVerifiedBlockNumbers(@NonNull final HapiSpec spec) {
        final var blockNodeNetwork = NetworkTargetingExtension.SHARED_BLOCK_NODE_NETWORK.get();
        if (blockNodeNetwork == null) {
            return Set.of();
        }
        return blockNodeNetwork.getSimulatedBlockNodeById().values().stream()
                .map(SimulatedBlockNodeServer::getReceivedBlockNumbers)
                .reduce(new HashSet<>(), (acc, nums) -> {
                    acc.addAll(nums);
                    return acc;
                });
    }
}
