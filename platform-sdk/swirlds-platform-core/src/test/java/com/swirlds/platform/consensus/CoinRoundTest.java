// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.config.legacy.LegacyConfigProperties;
import com.swirlds.platform.config.legacy.LegacyConfigPropertiesLoader;
import com.swirlds.platform.event.preconsensus.PcesFileReader;
import com.swirlds.platform.event.preconsensus.PcesFileTracker;
import com.swirlds.platform.event.preconsensus.PcesMultiFileIterator;
import com.swirlds.platform.event.preconsensus.PcesUtilities;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.roster.RosterRetriever;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class CoinRoundTest extends PlatformTest {

    @ParameterizedTest
    @ValueSource(strings = {"coin-round-test/0.62-20250514-101342/"})
    void coinRound(final String resources) throws URISyntaxException, IOException {
        final PlatformContext context = createDefaultPlatformContext();

        final Path dir = ResourceLoader.getFile(resources + "events");
        // this will compact files in advance. the PcesFileReader will do the same thing and the these files will be
        // in the gradle cache and break the test. this seems to bypass that issue.
        PcesUtilities.compactPreconsensusEventFiles(dir);

        final PcesFileTracker pcesFileTracker =
                PcesFileReader.readFilesFromDisk(context, dir, 0, false, AncientMode.GENERATION_THRESHOLD);

        final LegacyConfigProperties legacyConfigProperties =
                LegacyConfigPropertiesLoader.loadConfigFile(ResourceLoader.getFile(resources + "config.txt"));
        final TestIntake intake =
                new TestIntake(context, RosterRetriever.buildRoster(legacyConfigProperties.getAddressBook()));

        long maxGen = 0;
        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(0, 0);
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            maxGen = Math.max(maxGen, event.getGeneration());
            intake.addEvent(event);
        }

        assertMarkerFile(ConsensusImpl.COIN_ROUND_MARKER_FILE, true);
    }
}
