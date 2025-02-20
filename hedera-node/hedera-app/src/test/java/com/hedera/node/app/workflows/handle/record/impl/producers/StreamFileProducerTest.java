// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record.impl.producers;

import static com.hedera.node.app.records.RecordTestData.STARTING_RUNNING_HASH_OBJ;
import static com.hedera.node.app.records.RecordTestData.TEST_BLOCKS;
import static com.hedera.node.app.records.RecordTestData.VERSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.streams.HashObject;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.impl.producers.BlockRecordWriter;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;

@SuppressWarnings("DataFlowIssue")
@ExtendWith(MockitoExtension.class)
abstract class StreamFileProducerTest extends AppTestBase {
    private final AtomicInteger lastBlockNumber = new AtomicInteger(0);
    private final AtomicReference<Instant> lastConsensusTime = new AtomicReference<>(Instant.EPOCH);
    private final List<BlockRecordWriterDummy> writers = new CopyOnWriteArrayList<>();
    private BlockRecordStreamProducer subject;

    abstract BlockRecordStreamProducer createStreamProducer(@NonNull BlockRecordWriterFactory factory);

    @Nested
    @DisplayName("Initialization Tests")
    final class InitTests {
        @BeforeEach
        void setUp() {
            subject = createStreamProducer(BlockRecordWriterDummy::new);
        }

        @Test
        @DisplayName("RunningHashes cannot be null")
        void runningHashesCannotBeNull() {
            assertThatThrownBy(() -> subject.initRunningHash(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Initial running hash cannot be null")
        void runningHashCannotBeNull() {
            final var runningHashes = new RunningHashes(null, Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY);
            assertThatThrownBy(() -> subject.initRunningHash(runningHashes))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Initialization cannot be called twice")
        void initCannotBeCalledTwice() {
            final var runningHashes =
                    new RunningHashes(randomBytes(32), randomBytes(32), randomBytes(32), randomBytes(32));
            subject.initRunningHash(runningHashes);
            assertThatThrownBy(() -> subject.initRunningHash(runningHashes)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Initial values for runningHash and N-minus-3 match the provided runningHash")
        void runningHashAndNMinus3Match() {
            final var runningHashes =
                    new RunningHashes(randomBytes(32), randomBytes(32), randomBytes(32), randomBytes(32));
            subject.initRunningHash(runningHashes);
            assertThat(subject.getRunningHash()).isEqualTo(runningHashes.runningHash());
            assertThat(subject.getNMinus3RunningHash()).isEqualTo(runningHashes.nMinus3RunningHash());
        }

        @Test
        @DisplayName("Null is OK for older initial hashes")
        void nullOK() {
            final var runningHashes = new RunningHashes(randomBytes(32), null, null, null);
            subject.initRunningHash(runningHashes);
            assertThat(subject.getRunningHash()).isEqualTo(runningHashes.runningHash());
            assertThat(subject.getNMinus3RunningHash()).isEqualTo(Bytes.EMPTY);
        }
    }

    @Nested
    @DisplayName("Writing Tests")
    final class WritingTests {
        @BeforeEach
        void setUp() {
            subject = createStreamProducer(BlockRecordWriterDummy::new);
        }

        static Stream<Arguments> provideBlocks() {
            final var args = new ArrayList<Arguments>();
            for (int i = 0; i < TEST_BLOCKS.size(); i++) {
                args.add(Arguments.of(Named.of("Block " + i, TEST_BLOCKS.get(i))));
            }
            return args.stream();
        }

        @ParameterizedTest
        @MethodSource("provideBlocks")
        @DisplayName("Check invalid args to writeRecordStreamItems")
        void checkArgs_writeRecordStreamItems(@NonNull final List<SingleTransactionRecord> records) {
            final var consensusTime = Instant.now();
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            subject.switchBlocks(0, 1, consensusTime);
            assertThatThrownBy(() -> subject.writeRecordStreamItems(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Empty record stream is OK")
        void emptyRecordStream() {
            final var consensusTime = Instant.now();
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            subject.switchBlocks(0, 1, consensusTime);
            assertThatNoException().isThrownBy(() -> subject.writeRecordStreamItems(Stream.of()));
        }

        @ParameterizedTest
        @MethodSource("provideBlocks")
        @DisplayName("Writing a block")
        void writingABlock(@NonNull final List<SingleTransactionRecord> records) throws Exception {
            final var consensusTime = Instant.now();
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            subject.switchBlocks(0, 1, consensusTime);
            var rollingHash = STARTING_RUNNING_HASH_OBJ.hash();
            for (final var rec : records) {
                subject.writeRecordStreamItems(Stream.of(rec));

                // Check the rolling hash
                final var ser = BlockRecordFormatV6.INSTANCE.serialize(rec, 1, VERSION);
                rollingHash = BlockRecordFormatV6.INSTANCE.computeNewRunningHash(rollingHash, List.of(ser));
                assertThat(rollingHash).isEqualTo(subject.getRunningHash());
            }

            // We will now close the producer so as to cause it to finish all its work synchronously, so we can inspect
            // the final results.
            final var finalRunningHash = subject.getRunningHash();
            subject.close();
            final var writer = writers.get(0);
            assertThat(writer.blockNumber).isEqualTo(1);
            assertThat(writer.startConsensusTime).isEqualTo(consensusTime);
            assertThat(writer.closed).isTrue();
            assertThat(writer.endRunningHash.hash()).isEqualTo(finalRunningHash);
            assertThat(writer.hapiProtoVersion).isEqualTo(VERSION);
            assertThat(writer.records).hasSameSizeAs(records);
        }

        @Test
        @DisplayName("NMinus3 running hash is set")
        void nMinus3() throws Exception {
            final var consensusTime = Instant.now();
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            subject.switchBlocks(0, 1, consensusTime);
            final var records = TEST_BLOCKS.get(0); // Has at least 4 transactions
            subject.writeRecordStreamItems(Stream.of(records.get(0)));
            subject.writeRecordStreamItems(Stream.of(records.get(1)));
            subject.writeRecordStreamItems(Stream.of(records.get(2)));
            assertThat(subject.getNMinus3RunningHash()).isEqualTo(STARTING_RUNNING_HASH_OBJ.hash());
            subject.writeRecordStreamItems(Stream.of(records.get(3)));
            assertThat(subject.getNMinus3RunningHash()).isNotEqualTo(STARTING_RUNNING_HASH_OBJ.hash());
            subject.close();
        }
    }

    @Nested
    @DisplayName("Switching Blocks")
    final class SwitchingTests {
        @BeforeEach
        void setUp() {
            subject = createStreamProducer(BlockRecordWriterDummy::new);
        }

        @Test
        @DisplayName("Switching blocks with invalid args")
        void checkArgs_switchBlocks() {
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            assertThatThrownBy(() -> subject.switchBlocks(0, 1, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Switching blocks creates new writers")
        void switchingBlocks() throws Exception {
            int blockNum = 0;
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            for (final var records : TEST_BLOCKS) {
                final var consensusTime = Instant.now();
                subject.switchBlocks(blockNum, blockNum + 1, consensusTime);
                blockNum++;
                records.forEach(rec -> subject.writeRecordStreamItems(Stream.of(rec)));
            }

            // We will now close the producer so as to cause it to finish all its work synchronously, so we can inspect
            // the final results.
            subject.close();

            assertThat(writers).hasSameSizeAs(TEST_BLOCKS);
            for (int i = 0; i < writers.size(); i++) {
                final var writer = writers.get(i);
                final var records = TEST_BLOCKS.get(i);
                assertThat(writer.blockNumber).isEqualTo(i + 1);
                assertThat(writer.closed).isTrue();
                assertThat(writer.hapiProtoVersion).isEqualTo(VERSION);
                assertThat(writer.records).hasSameSizeAs(records);
            }
        }

        @Test
        @DisplayName("User transactions with multiple records")
        void multipleRecords() throws Exception {
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            subject.switchBlocks(0, 1, Instant.now());

            final var records = TEST_BLOCKS.get(0); // Has at least 4 transactions
            subject.writeRecordStreamItems(Stream.of(records.get(0), records.get(1), records.get(2)));

            // Submitting a batch of records only moves the running hash forward once, because it moves forward once
            // PER USER TRANSACTION, not per record.
            assertThat(subject.getNMinus3RunningHash()).isEqualTo(Bytes.EMPTY);

            final var finalRunningHash = subject.getRunningHash();
            subject.close();

            // We should find that there is a writer with those three records
            final var writer = writers.get(0);
            assertThat(writer.blockNumber).isEqualTo(1);
            assertThat(writer.endRunningHash.hash()).isEqualTo(finalRunningHash);
            assertThat(writer.hapiProtoVersion).isEqualTo(VERSION);
            assertThat(writer.records).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Negative Tests")
    final class NegativeTests {
        @Test
        @DisplayName("Exceptions that occur when closing an old writer do not prevent new writers from working")
        void errorClosingWriter() throws Exception {
            subject = createStreamProducer(() -> new BlockRecordWriterDummy() {
                @Override
                public void close(@NonNull final HashObject endRunningHash) {
                    throw new RuntimeException("Close throws!");
                }
            });

            final var consensusTime = Instant.now();
            subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));
            subject.switchBlocks(0, 1, consensusTime);

            // "switchBlocks" causes a new writer to be created, which will throw
            final var records = TEST_BLOCKS.get(0); // Has at least 4 transactions
            subject.writeRecordStreamItems(Stream.of(records.get(0)));
            subject.switchBlocks(1, 2, consensusTime.plusSeconds(2));
            subject.writeRecordStreamItems(Stream.of(records.get(1)));

            subject.close();

            // We should find that two writers exist.
            assertThat(writers).hasSize(2);
        }
    }

    private class BlockRecordWriterDummy implements BlockRecordWriter {
        private SemanticVersion hapiProtoVersion;
        private HashObject startRunningHash;
        private Instant startConsensusTime;
        private long blockNumber;
        private final List<SerializedSingleTransactionRecord> records = new LinkedList<>();
        private HashObject endRunningHash;

        private boolean initialized = false;
        private boolean closed = false;

        @Override
        public void init(
                @NonNull final SemanticVersion hapiProtoVersion,
                @NonNull final HashObject startRunningHash,
                @NonNull final Instant startConsensusTime,
                final long blockNumber) {
            assertThat(initialized).isFalse();
            assertThat(closed).isFalse();
            assertThat(hapiProtoVersion).isNotNull();
            assertThat(startRunningHash).isNotNull();
            assertThat(startConsensusTime).isNotNull();
            assertThat(startConsensusTime).isAfter(lastConsensusTime.getAndSet(startConsensusTime));
            assertThat(blockNumber).isEqualTo(lastBlockNumber.incrementAndGet());

            writers.add(this);
            this.hapiProtoVersion = hapiProtoVersion;
            this.startRunningHash = startRunningHash;
            this.startConsensusTime = startConsensusTime;
            this.blockNumber = blockNumber;
            this.initialized = true;
        }

        @Override
        public void writeItem(@NonNull final SerializedSingleTransactionRecord item) {
            assertThat(initialized).isTrue();
            assertThat(item).isNotNull();
            this.records.add(item);
        }

        @Override
        public void close(@NonNull final HashObject endRunningHash) {
            assertThat(initialized).isTrue();
            assertThat(closed).isFalse();
            assertThat(endRunningHash).isNotNull();
            this.endRunningHash = endRunningHash;
            this.closed = true;
        }
    }
}
