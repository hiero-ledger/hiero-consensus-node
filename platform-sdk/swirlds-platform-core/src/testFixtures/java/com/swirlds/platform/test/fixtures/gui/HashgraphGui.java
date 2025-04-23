// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.ForkingEventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class HashgraphGui {

    /**
     * The main method that runs the GUI. It creates a Randotron, a GraphGenerator, and a TestGuiSource.
     * It generates events and runs the GUI.
     *
     * @param args command line arguments - if "branch" is passed the GUI will have a forking event source and branched
     *             events will be shown
     */
    public static void main(final String[] args) {
        final Randotron randotron = Randotron.create(1);
        final int numNodes = 4;
        final int initialEvents = 50;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final GraphGenerator graphGenerator = new StandardGraphGenerator(
                platformContext,
                randotron.nextInt(),
                generateSources(numNodes, Arrays.stream(args).anyMatch("branch"::equals)));
        graphGenerator.reset();

        final TestGuiSource guiSource = new TestGuiSource(
                platformContext, graphGenerator.getAddressBook(), new GeneratorEventProvider(graphGenerator));
        guiSource.generateEvents(initialEvents);
        guiSource.runGui();
    }

    /**
     * Method that generates event source  for each node. It can also accept a boolean flag that
     * indicates if we should have a forking event source or not.
     *
     * @param numNetworkNodes the number of nodes in the network
     * @param shouldBranch true if we should have a forking event source, false otherwise
     */
    private static @NonNull List<EventSource> generateSources(final int numNetworkNodes, final boolean shouldBranch) {
        final List<EventSource> list = new LinkedList<>();
        for (long i = 0; i < numNetworkNodes; i++) {
            if (i == 1 && shouldBranch) {
                list.add(new ForkingEventSource().setForkProbability(0.8).setMaximumBranchCount(5));
                continue;
            }
            list.add(new StandardEventSource(true));
        }
        return list;
    }
}
