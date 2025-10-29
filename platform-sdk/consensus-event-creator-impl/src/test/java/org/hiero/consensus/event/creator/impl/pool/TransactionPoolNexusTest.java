// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hiero.base.utility.ByteUtils;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.transaction.TransactionLimits;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionPoolNexusTest {

    static final int MAX_TX_BYTES_PER_EVENT = 245_760;
    static final int TX_MAX_BYTES = 6_144;
    static final int TX_QUEUE_SIZE = 100_000;

    TransactionPoolNexus nexus;
    FakeTime fakeTime;

    @BeforeEach
    public void beforeEach() {
        fakeTime = new FakeTime();
        final TransactionLimits txConfig = new TransactionLimits(TX_MAX_BYTES, MAX_TX_BYTES_PER_EVENT);
        nexus = new TransactionPoolNexus(txConfig, TX_QUEUE_SIZE, new NoOpMetrics(), fakeTime);
        nexus.updatePlatformStatus(PlatformStatus.ACTIVE);
    }

    @ParameterizedTest
    @MethodSource("testSubmitApplicationTransactionArgs")
    void testSubmitApplicationTransaction(final int txNumBytes, final boolean shouldSucceed) {
        final Randotron rand = Randotron.create();
        final Bytes tx = Bytes.wrap(rand.nextByteArray(txNumBytes));

        assertEquals(shouldSucceed, nexus.submitApplicationTransaction(tx));
    }

    static List<Arguments> testSubmitApplicationTransactionArgs() {
        return List.of(
                Arguments.of(TX_MAX_BYTES - 1, true),
                Arguments.of(TX_MAX_BYTES, true),
                Arguments.of(TX_MAX_BYTES + 1, false));
    }

    @Test
    void testGetTransactionsWithLargeTransactions() {
        final Randotron rand = Randotron.create();

        // create several transactions of varying sizes and submit them, such that there will be multiple events
        int rem = MAX_TX_BYTES_PER_EVENT;
        int numCreated = 0;
        while (rem >= TX_MAX_BYTES) {
            final int txSize = rand.nextPositiveInt(TX_MAX_BYTES);
            rem -= txSize;
            numCreated++;
            final Bytes tx = Bytes.wrap(rand.nextByteArray(txSize));
            assertTrue(nexus.submitApplicationTransaction(tx));
        }

        // create one more transaction that is the max size. this tx will be forced into a new event
        final Bytes tx = Bytes.wrap(rand.nextByteArray(TX_MAX_BYTES));
        assertTrue(nexus.submitApplicationTransaction(tx));

        // get the transactions
        // this should happen in two batches, the first will all of the random size transactions created in the loop
        // above, followed by a second batch that should be just the single large transaction submitted last
        final List<TimestampedTransaction> firstBatch = nexus.getTransactionsForEvent();
        assertNotNull(firstBatch);
        assertEquals(numCreated, firstBatch.size());

        // loop through the transactions and make sure the size does not exceed what we expect
        final long firstBatchBytesLength = firstBatch.stream()
                .map(TimestampedTransaction::transaction)
                .map(Bytes::length)
                .reduce(0L, Long::sum);
        assertTrue(
                firstBatchBytesLength <= MAX_TX_BYTES_PER_EVENT,
                "Total number of bytes in the batch (" + firstBatchBytesLength + ") exceeds max allowed ("
                        + MAX_TX_BYTES_PER_EVENT + ")");

        // get the second batch; it should be just the final transaction
        final List<TimestampedTransaction> secondBatch = nexus.getTransactionsForEvent();
        assertNotNull(secondBatch);
        assertEquals(1, secondBatch.size());
        assertEquals(TX_MAX_BYTES, secondBatch.getFirst().transaction().length());

        // and just for fun, make sure there aren't any more batches
        final List<TimestampedTransaction> thirdBatch = nexus.getTransactionsForEvent();
        assertNotNull(thirdBatch);
        assertTrue(thirdBatch.isEmpty());
    }

    @Test
    void testTransactionTimestamps() {
        nexus.updatePlatformStatus(PlatformStatus.ACTIVE);

        // Record the initial time and advance it slightly to establish baseline
        final Instant initialTime = fakeTime.now();
        fakeTime.tick(Duration.ofMillis(10));

        // Submit first transaction
        final Instant firstTxTime = fakeTime.now();
        final Bytes firstTx = Bytes.wrap(ByteUtils.intToByteArray(1));
        assertTrue(nexus.submitApplicationTransaction(firstTx));

        // Advance time before submitting second transaction
        fakeTime.tick(Duration.ofMillis(100));
        final Instant secondTxTime = fakeTime.now();
        final Bytes secondTx = Bytes.wrap(ByteUtils.intToByteArray(2));
        assertTrue(nexus.submitApplicationTransaction(secondTx));

        // Advance time again before third transaction
        fakeTime.tick(Duration.ofSeconds(1));
        final Instant thirdTxTime = fakeTime.now();
        final Bytes thirdTx = Bytes.wrap(ByteUtils.intToByteArray(3));
        assertTrue(nexus.submitApplicationTransaction(thirdTx));

        // Get transactions for event
        final List<TimestampedTransaction> transactions = nexus.getTransactionsForEvent();

        // Validate we got all three transactions
        assertNotNull(transactions);
        assertEquals(3, transactions.size());

        // Validate timestamps are correct and in order
        final TimestampedTransaction firstTimestamped = transactions.get(0);
        final TimestampedTransaction secondTimestamped = transactions.get(1);
        final TimestampedTransaction thirdTimestamped = transactions.get(2);

        // Verify transaction data matches
        assertEquals(firstTx, firstTimestamped.transaction());
        assertEquals(secondTx, secondTimestamped.transaction());
        assertEquals(thirdTx, thirdTimestamped.transaction());

        // Verify timestamps match when transactions were submitted
        assertEquals(firstTxTime, firstTimestamped.receivedTime());
        assertEquals(secondTxTime, secondTimestamped.receivedTime());
        assertEquals(thirdTxTime, thirdTimestamped.receivedTime());

        // Verify timestamps are in chronological order
        assertTrue(firstTimestamped.receivedTime().isBefore(secondTimestamped.receivedTime()));
        assertTrue(secondTimestamped.receivedTime().isBefore(thirdTimestamped.receivedTime()));

        // Verify all timestamps are after initial time
        assertTrue(firstTimestamped.receivedTime().isAfter(initialTime));
        assertTrue(secondTimestamped.receivedTime().isAfter(initialTime));
        assertTrue(thirdTimestamped.receivedTime().isAfter(initialTime));
    }
}
