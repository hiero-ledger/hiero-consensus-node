// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;

public class HashgraphGui {
    public static void main(final String[] args) {
        final Randotron randotron = Randotron.create(1);
        final int numNodes = 4;
        final int initialEvents = 50;

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        final GraphGenerator graphGenerator =
                new StandardGraphGenerator(platformContext, randotron.nextInt(), generateSources(numNodes));
        graphGenerator.reset();

        final TestGuiSource guiSource = new TestGuiSource(
                platformContext, graphGenerator.getAddressBook(), new GeneratorEventProvider(graphGenerator));
        guiSource.generateEvents(initialEvents);
        guiSource.runGui();
    }

    private static @NonNull List<EventSource> generateSources(final int numNetworkNodes) {
        final List<EventSource> list = new LinkedList<>();
        for (long i = 0; i < numNetworkNodes; i++) {
            list.add(new StandardEventSource(true));
        }
        return list;
    }
}
