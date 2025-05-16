// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.test.fixtures.event.PcesWriterTestUtils;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.hashgraph.EventWindowBuilder;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DefaultInlinePcesWriterTest {

    @TempDir
    private Path tempDir;

    private final int numEvents = 1_000;
    private final NodeId selfId = NodeId.of(0);

    @NonNull
    private static PlatformContext buildContext(@NonNull final Configuration configuration) {
        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withTime(new FakeTime(Duration.ofMillis(1)))
                .build();
    }

    @NonNull
    private PlatformContext getPlatformContext(final AncientMode ancientMode) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PcesConfig_.DATABASE_DIRECTORY, tempDir.toString())
                .withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, ancientMode == BIRTH_ROUND_THRESHOLD)
                .getOrCreateConfig();
        return buildContext(configuration);
    }

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    void standardOperationTest(final AncientMode ancientMode) throws Exception {
        final PlatformContext platformContext = getPlatformContext(ancientMode);
        final Random random = RandomUtils.getRandomPrintSeed();

        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultInlinePcesWriter writer = new DefaultInlinePcesWriter(platformContext, fileManager, selfId);

        writer.beginStreamingNewEvents();
        for (final PlatformEvent event : events) {
            writer.writeEvent(event);
        }

        // forces the writer to close the current file so that we can verify the stream
        writer.registerDiscontinuity(1L);

        PcesWriterTestUtils.verifyStream(selfId, events, platformContext, 0, ancientMode);
    }

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    void ancientEventTest(final AncientMode ancientMode) throws Exception {

        final Random random = RandomUtils.getRandomPrintSeed();
        final PlatformContext platformContext = getPlatformContext(ancientMode);
        final StandardGraphGenerator generator = PcesWriterTestUtils.buildGraphGenerator(platformContext, random);

        final int stepsUntilAncient = random.nextInt(50, 100);
        final PcesFileTracker pcesFiles = new PcesFileTracker(ancientMode);

        final PcesFileManager fileManager = new PcesFileManager(platformContext, pcesFiles, selfId, 0);
        final DefaultInlinePcesWriter writer = new DefaultInlinePcesWriter(platformContext, fileManager, selfId);

        // We will add this event at the very end, it should be ancient by then
        final PlatformEvent ancientEvent = generator.generateEventWithoutIndex().getBaseEvent();

        final List<PlatformEvent> events = new LinkedList<>();
        for (int i = 0; i < numEvents; i++) {
            events.add(generator.generateEventWithoutIndex().getBaseEvent());
        }

        writer.beginStreamingNewEvents();

        long lowerBound = ancientMode.getGenesisIndicator();
        final Iterator<PlatformEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            final PlatformEvent event = iterator.next();

            writer.writeEvent(event);
            lowerBound = Math.max(lowerBound, ancientMode.selectIndicator(event) - stepsUntilAncient);

            writer.updateNonAncientEventBoundary(EventWindowBuilder.builder()
                    .setAncientMode(ancientMode)
                    .setAncientThreshold(lowerBound)
                    .setExpiredThreshold(lowerBound)
                    .build());

            if (ancientMode.selectIndicator(event) < lowerBound) {
                // Although it's not common, it's actually possible that the generator will generate
                // an event that is ancient (since it isn't aware of what we consider to be ancient)
                iterator.remove();
            }
        }

        if (lowerBound > ancientMode.selectIndicator(ancientEvent)) {
            // This is probably not possible... but just in case make sure this event is ancient
            try {
                writer.updateNonAncientEventBoundary(EventWindowBuilder.builder()
                        .setAncientMode(ancientMode)
                        .setAncientThreshold(ancientMode.selectIndicator(ancientEvent) + 1)
                        .setExpiredThreshold(ancientMode.selectIndicator(ancientEvent) + 1)
                        .build());
            } catch (final IllegalArgumentException e) {
                // ignore, more likely than not this event is way older than the actual ancient threshold
            }
        }

        // forces the writer to close the current file so that we can verify the stream
        writer.registerDiscontinuity(1L);

        PcesWriterTestUtils.verifyStream(selfId, events, platformContext, 0, ancientMode);
    }
}
