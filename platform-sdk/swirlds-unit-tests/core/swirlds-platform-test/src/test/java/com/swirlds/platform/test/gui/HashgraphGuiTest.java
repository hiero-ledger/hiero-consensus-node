/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.test.gui;

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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class HashgraphGuiTest {
    @Test
    @Disabled("this test is useful for debugging consensus")
    void runGuiWithControls() {
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
