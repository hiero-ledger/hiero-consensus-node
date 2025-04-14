// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.event;

import static com.swirlds.common.units.DataUnit.UNIT_BYTES;
import static com.swirlds.common.units.DataUnit.UNIT_KILOBYTES;
import static com.swirlds.platform.system.transaction.TransactionWrapperUtils.createAppPayloadWrapper;
import static org.hiero.base.utility.CompareTo.isGreaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.IOIterator;
import com.swirlds.common.test.fixtures.TransactionGenerator;
import com.swirlds.platform.event.preconsensus.DefaultInlinePcesWriter;
import com.swirlds.platform.event.preconsensus.PcesFile;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.transaction.TransactionWrapper;

public class PcesWriterTestUtils {
    private PcesWriterTestUtils() {}

    /**
     * Build a transaction generator.
     */
    public static TransactionGenerator buildTransactionGenerator() {

        final int transactionCount = 10;
        final int averageTransactionSizeInKb = 10;
        final int transactionSizeStandardDeviationInKb = 5;

        return (final Random random) -> {
            final TransactionWrapper[] transactions = new TransactionWrapper[transactionCount];
            for (int index = 0; index < transactionCount; index++) {

                final int transactionSize = (int) UNIT_KILOBYTES.convertTo(
                        Math.max(
                                1,
                                averageTransactionSizeInKb
                                        + random.nextDouble() * transactionSizeStandardDeviationInKb),
                        UNIT_BYTES);
                final byte[] bytes = new byte[transactionSize];
                random.nextBytes(bytes);

                transactions[index] = createAppPayloadWrapper(bytes);
            }
            return transactions;
        };
    }

    /**
     * Build an event generator.
     */
    public static StandardGraphGenerator buildGraphGenerator(
            @NonNull final PlatformContext platformContext, @NonNull final Random random) {
        Objects.requireNonNull(platformContext);
        final TransactionGenerator transactionGenerator = PcesWriterTestUtils.buildTransactionGenerator();

        return new StandardGraphGenerator(
                platformContext,
                random.nextLong(),
                new StandardEventSource().setTransactionGenerator(transactionGenerator),
                new StandardEventSource().setTransactionGenerator(transactionGenerator),
                new StandardEventSource().setTransactionGenerator(transactionGenerator),
                new StandardEventSource().setTransactionGenerator(transactionGenerator));
    }

    /**
     * Perform verification on a stream written by a {@link DefaultInlinePcesWriter}.
     *
     * @param events             the events that were written to the stream
     * @param platformContext    the platform context
     * @param truncatedFileCount the expected number of truncated files
     * @param ancientMode        the ancient mode
     */
    public static void verifyStream(
            @NonNull final NodeId selfId,
            @NonNull final List<PlatformEvent> events,
            @NonNull final PlatformContext platformContext,
            final int truncatedFileCount,
            @NonNull final AncientMode ancientMode)
            throws IOException {

        long lastAncientIdentifier = Long.MIN_VALUE;
        for (final PlatformEvent event : events) {
            lastAncientIdentifier = Math.max(lastAncientIdentifier, ancientMode.selectIndicator(event));
        }

        final PcesFileTracker pcesFiles = PcesFileReader.readFilesFromDisk(
                platformContext, PcesUtilities.getDatabaseDirectory(platformContext, selfId), 0, false, ancientMode);

        // Verify that the events were written correctly
        final PcesMultiFileIterator eventsIterator = pcesFiles.getEventIterator(0, 0);
        int index = 0;
        for (final PlatformEvent event : events) {
            assertTrue(
                    eventsIterator.hasNext(),
                    "Event with index %d was not found, %d events are expected".formatted(index, events.size()));
            assertEquals(event, eventsIterator.next());
            index++;
        }
        assertFalse(eventsIterator.hasNext(), "There should be no more events");
        assertEquals(truncatedFileCount, eventsIterator.getTruncatedFileCount());

        // Make sure things look good when iterating starting in the middle of the stream that was written
        final long startingLowerBound = lastAncientIdentifier / 2;
        final IOIterator<PlatformEvent> eventsIterator2 = pcesFiles.getEventIterator(startingLowerBound, 0);
        for (final PlatformEvent event : events) {
            if (ancientMode.selectIndicator(event) < startingLowerBound) {
                continue;
            }
            assertTrue(eventsIterator2.hasNext());
            assertEquals(event, eventsIterator2.next());
        }
        assertFalse(eventsIterator2.hasNext());

        // Iterating from a high ancient indicator should yield no events
        final IOIterator<PlatformEvent> eventsIterator3 = pcesFiles.getEventIterator(lastAncientIdentifier + 1, 0);
        assertFalse(eventsIterator3.hasNext());

        // Do basic validation on event files
        final List<PcesFile> files = new ArrayList<>();
        pcesFiles.getFileIterator(0, 0).forEachRemaining(files::add);

        // There should be at least 2 files.
        // Certainly many more, but getting the heuristic right on this is non-trivial.
        assertTrue(files.size() >= 2);

        // Sanity check each individual file
        int nextSequenceNumber = 0;
        Instant previousTimestamp = Instant.MIN;
        long previousMinimum = Long.MIN_VALUE;
        long previousMaximum = Long.MIN_VALUE;
        for (final PcesFile file : files) {
            assertEquals(nextSequenceNumber, file.getSequenceNumber());
            nextSequenceNumber++;
            assertTrue(isGreaterThanOrEqualTo(file.getTimestamp(), previousTimestamp));
            previousTimestamp = file.getTimestamp();
            assertTrue(file.getLowerBound() <= file.getUpperBound());
            assertTrue(file.getLowerBound() >= previousMinimum);
            previousMinimum = file.getLowerBound();
            assertTrue(file.getUpperBound() >= previousMaximum);
            previousMaximum = file.getUpperBound();

            try (final IOIterator<PlatformEvent> fileEvents = file.iterator(0)) {
                while (fileEvents.hasNext()) {
                    final PlatformEvent event = fileEvents.next();
                    assertTrue(ancientMode.selectIndicator(event) >= file.getLowerBound());
                    assertTrue(ancientMode.selectIndicator(event) <= file.getUpperBound());
                }
            } catch (final IOException ignored) {
                // hasNext() can throw an IOException if the file is truncated, in this case there is nothing to do
            }
        }
    }
}
