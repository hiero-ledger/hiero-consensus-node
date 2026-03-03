// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import static com.hedera.hapi.streams.schema.SidecarFileSchema.SIDECAR_RECORDS;
import static com.hedera.node.app.records.BlockRecordService.EPOCH;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.TAG_TYPE_BITS;
import static com.hedera.node.app.records.impl.producers.BlockRecordFormat.WIRE_TYPE_DELIMITED;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.BLOCKS_STATE_ID;
import static com.hedera.node.app.records.schemas.V0490BlockRecordSchema.RUNNING_HASHES_STATE_ID;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.node.base.BlockHashAlgorithm;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.hapi.streams.ContractBytecode;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.hapi.streams.RecordStreamFile;
import com.hedera.hapi.streams.RecordStreamItem;
import com.hedera.hapi.streams.SidecarFile;
import com.hedera.hapi.streams.SidecarMetadata;
import com.hedera.hapi.streams.SidecarType;
import com.hedera.hapi.streams.TransactionSidecarRecord;
import com.hedera.node.app.blocks.impl.BlockImplUtils;
import com.hedera.node.app.blocks.impl.IncrementalStreamingHasher;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.quiescence.QuiescedHeartbeat;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.node.config.data.VersionConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.HashingOutputStream;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.platformstate.V0540PlatformStateSchema;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BlockRecordManagerImplWrappedRecordFileBlockHashesTest extends AppTestBase {

    @Test
    void doesNotAppendWhenFeatureFlagDisabled() {
        final var app = appBuilder()
                .withService(new BlockRecordService())
                .withService(new PlatformStateService())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", false)
                .build();

        // The fixture doesn't run genesis migrations by default, so seed the block record singletons
        app.stateMutator(BlockRecordService.NAME)
                .withSingletonState(
                        BLOCKS_STATE_ID,
                        BlockInfo.newBuilder()
                                .lastBlockNumber(-1)
                                .firstConsTimeOfLastBlock(EPOCH)
                                .blockHashes(Bytes.EMPTY)
                                .consTimeOfLastHandledTxn(EPOCH)
                                .migrationRecordsStreamed(true)
                                .firstConsTimeOfCurrentBlock(EPOCH)
                                .lastUsedConsTime(EPOCH)
                                .lastIntervalProcessTime(EPOCH)
                                .build())
                .withSingletonState(
                        RUNNING_HASHES_STATE_ID,
                        RunningHashes.newBuilder()
                                .runningHash(Bytes.wrap(new byte[48]))
                                .build())
                .commit();

        // Seed platform state singleton needed by BlockRecordManagerImpl.startUserTransaction()
        app.stateMutator(PlatformStateService.NAME)
                .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, PlatformState.DEFAULT)
                .commit();

        final var state = requireNonNullState(app.workingStateAccessor().getState());
        final var producer = new FakeStreamProducer();
        final var controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(5)), InstantSource.system(), () -> 0);
        final var heartbeat = new QuiescedHeartbeat(controller, app.platform());
        final var diskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
        try (final var mgr = new BlockRecordManagerImpl(
                app.configProvider(),
                state,
                producer,
                controller,
                heartbeat,
                app.platform(),
                diskWriter,
                InitTrigger.RECONNECT)) {
            final var t0 = InstantUtils.instant(10, 1);
            mgr.startUserTransaction(t0, state);
            mgr.endUserTransaction(Stream.of(sampleTxnRecord(t0, List.of())), state);

            final var t1 = InstantUtils.instant(13, 1); // crosses logPeriod boundary (default 2s)
            mgr.startUserTransaction(t1, state);
        }
        verify(diskWriter, never()).appendAsync(any());
    }

    @Test
    void appendsExpectedHashesWhenFeatureFlagEnabled() throws Exception {
        final var app = appBuilder()
                .withService(new BlockRecordService())
                .withService(new PlatformStateService())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .withConfigValue("hedera.recordStream.sidecarMaxSizeMb", 1)
                .build();

        // The fixture doesn't run genesis migrations by default, so seed the block record singletons
        app.stateMutator(BlockRecordService.NAME)
                .withSingletonState(
                        BLOCKS_STATE_ID,
                        BlockInfo.newBuilder()
                                .lastBlockNumber(-1)
                                .firstConsTimeOfLastBlock(EPOCH)
                                .blockHashes(Bytes.EMPTY)
                                .consTimeOfLastHandledTxn(EPOCH)
                                .migrationRecordsStreamed(true)
                                .firstConsTimeOfCurrentBlock(EPOCH)
                                .lastUsedConsTime(EPOCH)
                                .lastIntervalProcessTime(EPOCH)
                                .build())
                .withSingletonState(
                        RUNNING_HASHES_STATE_ID,
                        RunningHashes.newBuilder()
                                .runningHash(Bytes.wrap(new byte[48]))
                                .build())
                .commit();

        // Seed platform state singleton needed by BlockRecordManagerImpl.startUserTransaction()
        app.stateMutator(PlatformStateService.NAME)
                .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, PlatformState.DEFAULT)
                .commit();

        final var state = requireNonNullState(app.workingStateAccessor().getState());

        final var producer = new FakeStreamProducer();
        final var controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(5)), InstantSource.system(), () -> 0);
        final var heartbeat = new QuiescedHeartbeat(controller, app.platform());
        final var diskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
        try (final var mgr = new BlockRecordManagerImpl(
                app.configProvider(),
                state,
                producer,
                controller,
                heartbeat,
                app.platform(),
                diskWriter,
                InitTrigger.RECONNECT)) {

            final var creationTime = new Timestamp(10, 1);
            final var t0 = InstantUtils.instant(creationTime.seconds(), creationTime.nanos());

            // Two large BYTECODE sidecars so rollover occurs at 1MB threshold
            final Bytes big = Bytes.wrap(new byte[800_000]);
            final var sidecar1 = TransactionSidecarRecord.newBuilder()
                    .consensusTimestamp(creationTime)
                    .bytecode(ContractBytecode.newBuilder()
                            .contractId(ContractID.DEFAULT)
                            .runtimeBytecode(big)
                            .build())
                    .build();
            final var sidecar2 = TransactionSidecarRecord.newBuilder()
                    .consensusTimestamp(creationTime)
                    .bytecode(ContractBytecode.newBuilder()
                            .contractId(ContractID.DEFAULT)
                            .runtimeBytecode(big)
                            .build())
                    .build();

            final var record = sampleTxnRecord(t0, List.of(sidecar1, sidecar2));

            mgr.startUserTransaction(t0, state);
            mgr.endUserTransaction(Stream.of(record), state);

            // Cross logPeriod boundary to end record block 0 and enqueue
            final var t1 = InstantUtils.instant(13, 1);
            mgr.startUserTransaction(t1, state);
            final var captor = ArgumentCaptor.forClass(WrappedRecordFileBlockHashesComputationInput.class);
            verify(diskWriter).appendAsync(captor.capture());
            final var input = captor.getValue();
            assertEquals(0, input.blockNumber());
            final var entry = WrappedRecordFileBlockHashesCalculator.compute(input);

            // Compute expected consensus_timestamp_hash
            final Bytes expectedConsensusTsHash = BlockImplUtils.hashLeaf(Timestamp.PROTOBUF.toBytes(creationTime));
            assertArrayEquals(
                    expectedConsensusTsHash.toByteArray(),
                    entry.consensusTimestampHash().toByteArray());

            // Compute expected output_items_tree_root_hash
            final var cfg = app.configProvider().getConfiguration();
            // Match BlockRecordManagerImpl: use the same semantic version the record-stream writer uses, i.e.
            // servicesVersion with build=configVersion (not VersionConfig.hapiVersion()).
            final SemanticVersion hapiProtoVersion = cfg.getConfigData(VersionConfig.class)
                    .servicesVersion()
                    .copyBuilder()
                    .build("" + cfg.getConfigData(HederaConfig.class).configVersion())
                    .build();

            final var expectedSidecarFiles =
                    List.of(new SidecarFile(List.of(sidecar1)), new SidecarFile(List.of(sidecar2)));
            final var expectedSidecarMetadata = List.of(
                    new SidecarMetadata(
                            new HashObject(HashAlgorithm.SHA_384, 48, v6SidecarFileHash(List.of(sidecar1))),
                            1,
                            List.copyOf(EnumSet.of(SidecarType.CONTRACT_BYTECODE))),
                    new SidecarMetadata(
                            new HashObject(HashAlgorithm.SHA_384, 48, v6SidecarFileHash(List.of(sidecar2))),
                            2,
                            List.copyOf(EnumSet.of(SidecarType.CONTRACT_BYTECODE))));

            final var recordStreamItems =
                    List.of(new RecordStreamItem(record.transaction(), record.transactionRecord()));
            final var recordFileContents = new RecordStreamFile(
                    hapiProtoVersion,
                    new HashObject(HashAlgorithm.SHA_384, 48, producer.getRunningHash()),
                    recordStreamItems,
                    new HashObject(HashAlgorithm.SHA_384, 48, producer.getRunningHash()),
                    0,
                    expectedSidecarMetadata);

            final var recordFileItem = RecordFileItem.newBuilder()
                    .creationTime(creationTime)
                    .recordFileContents(recordFileContents)
                    .sidecarFileContents(expectedSidecarFiles)
                    .build();

            final var header = BlockHeader.newBuilder()
                    .hapiProtoVersion(hapiProtoVersion)
                    .number(0)
                    .blockTimestamp(creationTime)
                    .hashAlgorithm(BlockHashAlgorithm.SHA2_384);

            final var headerItem = BlockItem.newBuilder().blockHeader(header).build();
            final var recordFileBlockItem =
                    BlockItem.newBuilder().recordFile(recordFileItem).build();

            final var hasher = new IncrementalStreamingHasher(
                    MessageDigest.getInstance(DigestType.SHA_384.algorithmName()), List.of(), 0);
            hasher.addLeaf(BlockItem.PROTOBUF.toBytes(headerItem).toByteArray());
            hasher.addLeaf(BlockItem.PROTOBUF.toBytes(recordFileBlockItem).toByteArray());
            final Bytes expectedOutputRoot = Bytes.wrap(hasher.computeRootHash());

            assertArrayEquals(
                    expectedOutputRoot.toByteArray(),
                    entry.outputItemsTreeRootHash().toByteArray());
        }
    }

    @Test
    void doesNotCrashOrAppendIfNoCapturedItemsOnRestart() {
        final var app = appBuilder()
                .withService(new BlockRecordService())
                .withService(new PlatformStateService())
                .withConfigValue("hedera.recordStream.writeWrappedRecordFileBlockHashesToDisk", true)
                .build();

        // Seed state as if we are restarting mid-stream with an existing last block hash
        final var someHash = Bytes.wrap(new byte[BlockRecordInfoUtils.HASH_SIZE]);
        app.stateMutator(BlockRecordService.NAME)
                .withSingletonState(
                        BLOCKS_STATE_ID,
                        BlockInfo.newBuilder()
                                .lastBlockNumber(7)
                                .firstConsTimeOfLastBlock(EPOCH)
                                .blockHashes(someHash)
                                .consTimeOfLastHandledTxn(EPOCH)
                                .migrationRecordsStreamed(true)
                                .firstConsTimeOfCurrentBlock(EPOCH)
                                .lastUsedConsTime(EPOCH)
                                .lastIntervalProcessTime(EPOCH)
                                .build())
                .withSingletonState(
                        RUNNING_HASHES_STATE_ID,
                        RunningHashes.newBuilder()
                                .runningHash(Bytes.wrap(new byte[48]))
                                .build())
                .commit();

        app.stateMutator(PlatformStateService.NAME)
                .withSingletonState(V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID, PlatformState.DEFAULT)
                .commit();

        final var state = requireNonNullState(app.workingStateAccessor().getState());
        final var producer = new FakeStreamProducer();
        final var controller = new QuiescenceController(
                new QuiescenceConfig(false, Duration.ofSeconds(5)), InstantSource.system(), () -> 0);
        final var heartbeat = new QuiescedHeartbeat(controller, app.platform());
        final var diskWriter = mock(WrappedRecordFileBlockHashesDiskWriter.class);
        try (final var mgr = new BlockRecordManagerImpl(
                app.configProvider(),
                state,
                producer,
                controller,
                heartbeat,
                app.platform(),
                diskWriter,
                InitTrigger.RECONNECT)) {
            // Trigger a block boundary immediately; since no endUserTransaction was called, there are no captured
            // items.
            final var t1 = InstantUtils.instant(13, 1);
            mgr.startUserTransaction(t1, state);
        }
        verify(diskWriter, never()).appendAsync(any());
    }

    private static State requireNonNullState(final State state) {
        assertNotNull(state);
        return state;
    }

    private static SingleTransactionRecord sampleTxnRecord(
            final java.time.Instant consensusTime, final List<TransactionSidecarRecord> sidecars) {
        final var ts = new Timestamp(consensusTime.getEpochSecond(), consensusTime.getNano());
        final var txnRecord = com.hedera.hapi.node.transaction.TransactionRecord.newBuilder()
                .consensusTimestamp(ts)
                .build();
        return new SingleTransactionRecord(
                com.hedera.hapi.node.base.Transaction.DEFAULT,
                txnRecord,
                sidecars,
                new SingleTransactionRecord.TransactionOutputs(null));
    }

    private static Bytes v6SidecarFileHash(final List<TransactionSidecarRecord> records) throws Exception {
        final var digest = MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        final var gzip = new GZIPOutputStream(OutputStream.nullOutputStream());
        final var hashingOutputStream = new HashingOutputStream(digest, gzip);
        final var outputStream = new WritableStreamingData(new BufferedOutputStream(hashingOutputStream));
        for (final var record : records) {
            final var recordBytes = TransactionSidecarRecord.PROTOBUF.toBytes(record);
            outputStream.writeVarInt((SIDECAR_RECORDS.number() << TAG_TYPE_BITS) | WIRE_TYPE_DELIMITED, false);
            outputStream.writeVarInt((int) recordBytes.length(), false);
            outputStream.writeBytes(recordBytes);
        }
        outputStream.close();
        gzip.close();
        return Bytes.wrap(hashingOutputStream.getDigest());
    }

    /**
     * A minimal record-stream producer for tests. Keeps a constant running hash.
     */
    private static final class FakeStreamProducer implements BlockRecordStreamProducer {
        private Bytes runningHash = Bytes.wrap(new byte[48]);

        @Override
        public void initRunningHash(final com.hedera.hapi.node.state.blockrecords.RunningHashes runningHashes) {
            runningHash = runningHashes.runningHash();
        }

        @Override
        public Bytes getRunningHash() {
            return runningHash;
        }

        @Override
        public Bytes getNMinus3RunningHash() {
            return null;
        }

        @Override
        public void switchBlocks(
                final long lastBlockNumber,
                final long newBlockNumber,
                final java.time.Instant newBlockFirstTransactionConsensusTime) {
            // no-op
        }

        @Override
        public void writeRecordStreamItems(final Stream<SingleTransactionRecord> recordStreamItems) {
            // consume stream
            recordStreamItems.forEach(ignore -> {});
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * Utility: avoid importing Instant in many places.
     */
    private static final class InstantUtils {
        private static java.time.Instant instant(final long seconds, final int nanos) {
            return java.time.Instant.ofEpochSecond(seconds, nanos);
        }
    }
}
