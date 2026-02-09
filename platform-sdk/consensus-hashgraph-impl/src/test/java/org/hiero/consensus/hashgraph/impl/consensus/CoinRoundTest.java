// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus;

import static com.swirlds.platform.test.fixtures.PlatformTestUtils.createDefaultPlatformContext;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.io.ResourceLoader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.TestIntake;
import org.hiero.consensus.io.IOIterator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.pces.impl.test.fixtures.PcesFileIteratorFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class CoinRoundTest {

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
        final TestIntake intake = new TestIntake(context, Roster.newBuilder().build());
        try (final IOIterator<PlatformEvent> eventIterator = PcesFileIteratorFactory.createIterator(dir)) {
            while (eventIterator.hasNext()) {
                final PlatformEvent event = eventIterator.next();
                intake.addEvent(event);
            }
        }
    }
}
