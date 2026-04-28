// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gui;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.hiero.consensus.gui.api.TestGuiSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.metrics.noop.NoOpMetrics;

/**
 * Main class for running the hashgraph GUI with generated events.
 */
public class HashgraphGuiMain {

    /**
     * The main method that runs the GUI, showing a randomly generated graph.
     *
     * @param args command line arguments
     */
    public static void main(final String[] args) {
        final int initialEvents = 20;

        final Configuration defaultConfig = new TestConfigBuilder().getOrCreateConfig();

        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(4)
                .maxOtherParents(2)
                .seed(0)
                .build();

        final TestGuiSource guiSource =
                new TestGuiSource(new NoOpMetrics(), defaultConfig, generator.getRoster(), generator);
        guiSource.generateEvents(initialEvents);
        guiSource.runGui();
    }
}
