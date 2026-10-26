// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.containers;

import static com.hedera.pbj.runtime.Codec.DEFAULT_MAX_DEPTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import io.helidon.common.tls.Tls;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.block.api.BlockEnd;
import org.hiero.block.api.BlockItemSet;
import org.hiero.block.api.SubscribeStreamResponse;
import org.junit.jupiter.api.Test;

class BlockNodeSubscribeClientTest {
    @Test
    void configuredReplyLimitParsesBatchesLargerThanTwoMiB() throws ParseException {
        final var response = items(
                header(44),
                BlockItem.newBuilder()
                        .signedTransaction(Bytes.wrap(new byte[3 * 1024 * 1024]))
                        .build());
        final var bytes = SubscribeStreamResponse.PROTOBUF.toBytes(response);
        assertThrows(ParseException.class, () -> SubscribeStreamResponse.PROTOBUF.parse(bytes));
        final var config = BlockNodeSubscribeClient.clientConfig(
                Tls.builder().enabled(false).build());
        assertEquals(
                response,
                SubscribeStreamResponse.PROTOBUF.parse(
                        bytes.toReadableSequentialData(), false, false, DEFAULT_MAX_DEPTH, config.maxSize()));
        assertTrue(config.maxIncomingBufferSize() > config.maxSize());
    }

    @Test
    void returnsCompleteOrderedRangeAcrossItemBatches() throws InterruptedException {
        final var collector = new BlockNodeSubscribeClient.BlockCollector(44, 45);
        collector.onNext(items(header(44)));
        collector.onNext(items(
                BlockItem.newBuilder().signedTransaction(Bytes.wrap("txn")).build()));
        collector.onNext(end(44));
        collector.onNext(items(header(45)));
        collector.onNext(end(45));
        collector.onNext(success());
        collector.onComplete();
        final var blocks = collector.await(Duration.ZERO);
        assertEquals(2, blocks.size());
        assertEquals(2, blocks.getFirst().items().size());
        assertEquals(
                45, blocks.getLast().items().getFirst().blockHeaderOrThrow().number());
    }

    @Test
    void propagatesParserFailureInsteadOfReturningCompletedPrefix() {
        final var collector = oneCompletedBlockOfTwo();
        final var cause = new ParseException("oversized block_items");
        collector.onError(cause);
        collector.onComplete();
        final var failure = assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
        assertSame(cause, failure.getCause());
    }

    @Test
    void rejectsSuccessfulButTruncatedRange() {
        final var collector = oneCompletedBlockOfTwo();
        collector.onNext(success());
        collector.onComplete();
        assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
    }

    @Test
    void rejectsMissingFinalBlockBoundary() {
        final var collector = new BlockNodeSubscribeClient.BlockCollector(44, 44);
        collector.onNext(items(header(44)));
        collector.onNext(success());
        collector.onComplete();
        assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
    }

    @Test
    void rejectsNonSuccessStatusAndMissingStatus() {
        final var collector = oneCompletedBlockOfTwo();
        collector.onNext(SubscribeStreamResponse.newBuilder()
                .status(SubscribeStreamResponse.Code.NOT_AVAILABLE)
                .build());
        assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
        final var missingStatus = new BlockNodeSubscribeClient.BlockCollector(44, 44);
        missingStatus.onNext(items(header(44)));
        missingStatus.onNext(end(44));
        missingStatus.onComplete();
        assertThrows(IllegalStateException.class, () -> missingStatus.await(Duration.ZERO));
    }

    @Test
    void rejectsSkippedBlocksAndMismatchingHeader() {
        final var collector = oneCompletedBlockOfTwo();
        collector.onNext(items(header(46)));
        collector.onNext(end(46));
        assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
        final var wrongHeader = new BlockNodeSubscribeClient.BlockCollector(44, 44);
        wrongHeader.onNext(items(header(43)));
        wrongHeader.onNext(end(44));
        assertThrows(IllegalStateException.class, () -> wrongHeader.await(Duration.ZERO));
    }

    @Test
    void timeoutDoesNotReturnPrefixAndCancelledCollectorIgnoresLateData() {
        final var collector = oneCompletedBlockOfTwo();
        final var cancelled = new AtomicBoolean();
        collector.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {}

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
        assertFalse(cancelled.get());
        collector.cancel();
        assertTrue(cancelled.get());
        collector.onNext(items(header(45)));
        collector.onNext(end(45));
        collector.onNext(success());
        collector.onComplete();
        assertThrows(IllegalStateException.class, () -> collector.await(Duration.ZERO));
    }

    private static BlockNodeSubscribeClient.BlockCollector oneCompletedBlockOfTwo() {
        final var collector = new BlockNodeSubscribeClient.BlockCollector(44, 45);
        collector.onNext(items(header(44)));
        collector.onNext(end(44));
        return collector;
    }

    private static BlockItem header(final long number) {
        return BlockItem.newBuilder()
                .blockHeader(BlockHeader.newBuilder().number(number).build())
                .build();
    }

    private static SubscribeStreamResponse items(final BlockItem... items) {
        return SubscribeStreamResponse.newBuilder()
                .blockItems(new BlockItemSet(List.of(items)))
                .build();
    }

    private static SubscribeStreamResponse end(final long number) {
        return SubscribeStreamResponse.newBuilder()
                .endOfBlock(new BlockEnd(number))
                .build();
    }

    private static SubscribeStreamResponse success() {
        return SubscribeStreamResponse.newBuilder()
                .status(SubscribeStreamResponse.Code.SUCCESS)
                .build();
    }
}
