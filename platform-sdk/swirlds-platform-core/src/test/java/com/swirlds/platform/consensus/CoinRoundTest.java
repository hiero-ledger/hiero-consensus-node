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
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.roster.RosterRetriever;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class CoinRoundTest extends PlatformTest {

    /**
     * A test that reads in a set of PCES event files and checks that the coin round occurred. The test expects the
     * following directory structure:
     * <ol>
     *     <li>supplied-dir/config.txt</li>
     *     <li>supplied-dir/events/*.pces</li>
     * </ol>
     */
    @ParameterizedTest
    @ValueSource(strings = {"coin-round-test/0.62-20250514-101342/"})
    @Disabled("This test used to work with PCES files that had generations in them but not birth rounds. "
            + "Since ancient threshold migration we no longer support these old files. "
            + "Once a coin round occurs with birth rounds new PCES files can be added and this test can be re-enabled.")
    void coinRound(final String resources) throws URISyntaxException, IOException {
        final PlatformContext context = createDefaultPlatformContext();

        final Path dir = ResourceLoader.getFile(resources + "events");
        // this will compact files in advance. the PcesFileReader will do the same thing and the these files will be
        // in the gradle cache and break the test. this seems to bypass that issue.
        PcesUtilities.compactPreconsensusEventFiles(dir);

        final PcesFileTracker pcesFileTracker =
                PcesFileReader.readFilesFromDisk(context.getConfiguration(), context.getRecycleBin(), dir, 0, false);

        final LegacyConfigProperties legacyConfigProperties =
                LegacyConfigPropertiesLoader.loadConfigFile(ResourceLoader.getFile(resources + "config.txt"));
        final TestIntake intake =
                new TestIntake(context, RosterRetriever.buildRoster(legacyConfigProperties.getAddressBook()));

        final PcesMultiFileIterator eventIterator = pcesFileTracker.getEventIterator(0, 0);
        while (eventIterator.hasNext()) {
            final PlatformEvent event = eventIterator.next();
            intake.addEvent(event);
        }

        assertMarkerFile(ConsensusImpl.COIN_ROUND_MARKER_FILE, true);
    }
}
