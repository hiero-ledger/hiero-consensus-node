// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GeneratorEventGraphSourceBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.gui.runner.TestGuiSource;

/**
 * Main class for running the hashgraph GUI.
 */
public class HashgraphGuiMain {

    /**
     * The main method that runs the GUI. It creates a Randotron, a GraphGenerator, and a TestGuiSource.
     * It generates events and runs the GUI.
     *
     * @param args command line arguments - if "branch" is passed the GUI will have a branching event source and branched
     *             events will be shown
     */
    public static void main(final String[] args) {
        final int initialEvents = 20;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final GeneratorEventGraphSource generator = GeneratorEventGraphSourceBuilder.builder()
                .numNodes(4)
                .maxOtherParents(2)
                .seed(0)
                .build();

        final TestGuiSource guiSource = new TestGuiSource(platformContext, generator.getRoster(), generator);
        guiSource.generateEvents(initialEvents);
        guiSource.runGui();
    }
}
