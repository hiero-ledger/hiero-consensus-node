// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.base.time.internal.OSTime;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.config.TransactionConfig;
import org.hiero.consensus.event.creator.impl.config.EventCreationConfig;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionPoolNexusTest {

    static final int MAX_TX_BYTES_PER_EVENT = 245_760;
    static final int TX_MAX_BYTES = 6_144;

    TransactionPoolNexus nexus;

    @BeforeEach
    public void beforeEach() {
        final TransactionConfig txConfig =
                new TransactionConfig(TX_MAX_BYTES, MAX_TX_BYTES_PER_EVENT, 245_760, 100_000);
        final EventCreationConfig eventCreationConfig =
                new EventCreationConfig(20, 100, 10, 10, 1024, Duration.ofSeconds(1));
        final Configuration configuration = mock(Configuration.class);
        when(configuration.getConfigData(TransactionConfig.class)).thenReturn(txConfig);
        when(configuration.getConfigData(EventCreationConfig.class)).thenReturn(eventCreationConfig);
        final Metrics metrics = new NoOpMetrics();
        final Time time = OSTime.getInstance();

        nexus = new TransactionPoolNexus(configuration, metrics, time);
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
        final List<Bytes> firstBatch = nexus.getTransactions();
        assertNotNull(firstBatch);
        assertEquals(numCreated, firstBatch.size());

        // loop through the transactions and make sure the size does not exceed what we expect
        final long firstBatchBytesLength =
                firstBatch.stream().map(Bytes::length).reduce(0L, Long::sum);
        assertTrue(
                firstBatchBytesLength <= MAX_TX_BYTES_PER_EVENT,
                "Total number of bytes in the batch (" + firstBatchBytesLength + ") exceeds max allowed ("
                        + MAX_TX_BYTES_PER_EVENT + ")");

        // get the second batch; it should be just the final transaction
        final List<Bytes> secondBatch = nexus.getTransactions();
        assertNotNull(secondBatch);
        assertEquals(1, secondBatch.size());
        assertEquals(TX_MAX_BYTES, secondBatch.getFirst().length());

        // and just for fun, make sure there aren't any more batches
        final List<Bytes> thirdBatch = nexus.getTransactions();
        assertNotNull(thirdBatch);
        assertTrue(thirdBatch.isEmpty());
    }
}
