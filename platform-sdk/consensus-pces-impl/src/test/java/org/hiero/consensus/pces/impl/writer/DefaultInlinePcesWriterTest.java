// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.StandardGraphGenerator;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.io.RecycleBinImpl;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.hiero.consensus.pces.config.PcesConfig_;
import org.hiero.consensus.pces.impl.common.CommonPcesWriter;
import org.hiero.consensus.pces.impl.common.PcesFileManager;
import org.hiero.consensus.pces.impl.common.PcesFileReader;
import org.hiero.consensus.pces.impl.common.PcesFileTracker;
import org.hiero.consensus.pces.impl.common.PcesMultiFileIterator;
import org.hiero.consensus.test.fixtures.io.TestRecycleBin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultInlinePcesWriterTest {

    private static final Time TIME = new FakeTime(Duration.ofMillis(1));
    private static final Metrics METRICS = new NoOpMetrics();
    private static final RecycleBin recycleBin = TestRecycleBin.getInstance();
    private static Configuration configuration;

    @TempDir
    private static Path tempDir;

    private final int numEvents = 1_000;
    private final NodeId selfId = NodeId.of(0);

    @BeforeAll
    static void setup() {
        configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, tempDir.toString())
                .getOrCreateConfig();
    }

    @Test
    void standardOperationTest() throws Exception {
        final Random random = RandomUtils.getRandomPrintSeed();

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(configuration, METRICS, TIME,
                random);

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex());
        }

        final PcesFileTracker pcesFiles = new PcesFileTracker();

        final PcesFileManager fileManager = new PcesFileManager(configuration, METRICS, TIME, pcesFiles, tempDir, 0);
        final CommonPcesWriter commonPcesWriter = new CommonPcesWriter(configuration, fileManager);
        final DefaultInlinePcesWriter writer =
                new DefaultInlinePcesWriter(configuration, METRICS, TIME, commonPcesWriter, selfId);

        writer.beginStreamingNewEvents();
        for (final PlatformEvent event : events) {
            writer.writeEvent(event);
        }

        // forces the writer to close the current file so that we can verify the stream
        writer.registerDiscontinuity(1L);

        PcesWriterTestUtils.verifyStream(tempDir, events, configuration, recycleBin, 0);
    }

    /**
     * Verify that after syncCurrentFile(), data is readable from disk even though the file has not been closed. This
     * simulates the guarantee needed for the shutdown hook and the flush-during-freeze path.
     */
    @Test
    void syncWithoutCloseTest() throws Exception {
        final Random random = RandomUtils.getRandomPrintSeed();

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(configuration, METRICS, TIME, random);

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex());
        }

        final PcesFileTracker pcesFiles = new PcesFileTracker();

        final PcesFileManager fileManager = new PcesFileManager(configuration, METRICS, TIME, pcesFiles, tempDir, 0);
        final CommonPcesWriter commonPcesWriter = new CommonPcesWriter(configuration, fileManager);
        final DefaultInlinePcesWriter writer =
                new DefaultInlinePcesWriter(configuration, METRICS, TIME, commonPcesWriter, selfId);

        writer.beginStreamingNewEvents();
        for (final PlatformEvent event : events) {
            writer.writeEvent(event);
        }

        // Sync without closing — this is what the shutdown hook and flush() do
        commonPcesWriter.syncCurrentFile();

        // Read events back from disk. The file is still open, but synced data should be readable.
        final PcesFileTracker readFiles =
                PcesFileReader.readFilesFromDisk(configuration, recycleBin, tempDir, 0, false);
        final PcesMultiFileIterator eventsIterator = readFiles.getEventIterator(0, 0);

        int count = 0;
        for (final PlatformEvent event : events) {
            assertTrue(eventsIterator.hasNext(), "Expected event at index " + count);
            assertEquals(event, eventsIterator.next());
            count++;
        }
        assertFalse(eventsIterator.hasNext(), "There should be no more events");

        // Now close properly for cleanup
        commonPcesWriter.closeCurrentMutableFile();
    }

    @Test
    void ancientEventTest() throws Exception {

        final Random random = RandomUtils.getRandomPrintSeed();
        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(configuration, METRICS, TIME, random);

        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesFileTracker pcesFiles = new PcesFileTracker();

        final PcesFileManager fileManager = new PcesFileManager(configuration, METRICS, TIME, pcesFiles, tempDir, 0);
        final CommonPcesWriter commonPcesWriter = new CommonPcesWriter(configuration, fileManager);
        final DefaultInlinePcesWriter writer =
                new DefaultInlinePcesWriter(configuration, METRICS, TIME, commonPcesWriter, selfId);

        // We will add this event at the very end, it should be ancient by then
        final PlatformEvent ancientEvent = generator.generateEventWithoutIndex();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex());
        }

        writer.beginStreamingNewEvents();

        long lowerBound = ConsensusConstants.ROUND_FIRST;
        final Iterator<PlatformEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            final PlatformEvent event = iterator.next();

            writer.writeEvent(event);
            lowerBound = Math.max(lowerBound, event.getBirthRound() - stepsUntilAncient);

            writer.updateNonAncientEventBoundary(EventWindowBuilder.builder()
                    .setAncientThreshold(lowerBound)
                    .setExpiredThreshold(lowerBound)
                    .build());

            if (event.getBirthRound() < lowerBound) {
                // Although it's not common, it's actually possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                iterator.remove();
            }
        }

        if (lowerBound > ancientEvent.getBirthRound()) {
            // This is probably not possible... but just in case make sure this event is ancient
            try {
                writer.updateNonAncientEventBoundary(EventWindowBuilder.builder()
                        .setAncientThreshold(ancientEvent.getBirthRound() + 1)
                        .setExpiredThreshold(ancientEvent.getBirthRound() + 1)
                        .build());
            } catch (final IllegalArgumentException e) {
                // ignore, more likely than not this event is way older than the actual ancient threshold
            }
        }

        // forces the writer to close the current file so that we can verify the stream
        writer.registerDiscontinuity(1L);

        PcesWriterTestUtils.verifyStream(tempDir, events, configuration, recycleBin, 0);
    }
}
