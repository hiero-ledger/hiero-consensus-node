// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record.impl.producers;

import static com.hedera.node.app.records.RecordTestData.STARTING_RUNNING_HASH_OBJ;
import static com.hedera.node.app.records.RecordTestData.VERSION;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.streams.HashObject;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.records.impl.producers.BlockRecordWriter;
import com.hedera.node.app.records.impl.producers.BlockRecordWriterFactory;
import com.hedera.node.app.records.impl.producers.StreamFileProducerConcurrent;
import com.hedera.node.app.records.impl.producers.formats.v6.BlockRecordFormatV6;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StreamFileProducerConcurrentTest extends StreamFileProducerTest {
    @Override
    BlockRecordStreamProducer createStreamProducer(@NonNull final BlockRecordWriterFactory factory) {
        return new StreamFileProducerConcurrent(
                BlockRecordFormatV6.INSTANCE, factory, ForkJoinPool.commonPool(), VERSION);
    }

    @Test
    void waitsForDetachedCloseToFinishBeforeOpeningNextWriter() throws Exception {
        final var closeStarted = new CountDownLatch(1);
        final var allowClose = new CountDownLatch(1);
        final var secondWriterInitialized = new CountDownLatch(1);
        final var writerNumber = new AtomicInteger(0);
        final var subject = createStreamProducer(() -> {
            final int thisWriterNumber = writerNumber.incrementAndGet();
            return new BlockRecordWriter() {
                @Override
                public void init(
                        @NonNull final com.hedera.hapi.node.base.SemanticVersion hapiProtoVersion,
                        @NonNull final HashObject startRunningHash,
                        @NonNull final Instant startConsensusTime,
                        final long blockNumber) {
                    if (thisWriterNumber == 2) {
                        secondWriterInitialized.countDown();
                    }
                }

                @Override
                public void writeItem(
                        @NonNull
                                final com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord
                                        item) {
                    // no-op
                }

                @Override
                public void close(@NonNull final HashObject endRunningHash) {
                    if (thisWriterNumber == 1) {
                        closeStarted.countDown();
                        try {
                            assertThat(allowClose.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (final InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                }
            };
        });
        final var consensusTime = Instant.now();
        subject.initRunningHash(new RunningHashes(STARTING_RUNNING_HASH_OBJ.hash(), null, null, null));

        subject.switchBlocks(0, 1, consensusTime);
        subject.closeBlock(1);
        assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();

        subject.switchBlocks(1, 2, consensusTime.plusSeconds(2));
        assertThat(secondWriterInitialized.await(200, TimeUnit.MILLISECONDS)).isFalse();

        allowClose.countDown();
        assertThat(secondWriterInitialized.await(5, TimeUnit.SECONDS)).isTrue();

        subject.close();
    }
}
