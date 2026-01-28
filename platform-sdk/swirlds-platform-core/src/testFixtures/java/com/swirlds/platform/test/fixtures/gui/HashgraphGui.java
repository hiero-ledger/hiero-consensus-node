// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.generator.GeneratorConfig;
import com.swirlds.platform.test.fixtures.event.generator.SimpleGraphGenerator;

public class HashgraphGui {

    /**
     * The main method that runs the GUI. It creates a Randotron, a GraphGenerator, and a TestGuiSource.
     * It generates events and runs the GUI.
     *
     * @param args command line arguments - if "branch" is passed the GUI will have a branching event source and branched
     *             events will be shown
     */
    public static void main(final String[] args) {
        final Randotron randotron = Randotron.create(1);
        final int numNodes = 4;
        final int initialEvents = 50;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final Roster roster = RandomRosterBuilder.create(randotron).withSize(numNodes).build();
        final SimpleGraphGenerator generator = new SimpleGraphGenerator(
                platformContext.getConfiguration(),
                platformContext.getTime(),
                new GeneratorConfig(0, 2),
                roster
        );

        final TestGuiSource guiSource = new TestGuiSource(
                platformContext, roster, new SimpleGeneratorProvider(generator));
        guiSource.generateEvents(initialEvents);
        guiSource.runGui();
    }
}
