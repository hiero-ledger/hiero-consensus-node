/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

import com.swirlds.platform.event.PlatformEvent;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * Provides events for the GUI by generating them using a {@link GraphGenerator}
 */
public class GeneratorEventProvider implements GuiEventProvider {
    private final GraphGenerator graphGenerator;

    /**
     * Constructor
     *
     * @param graphGenerator the graph generator
     */
    public GeneratorEventProvider(@NonNull final GraphGenerator graphGenerator) {
        Objects.requireNonNull(graphGenerator);
        this.graphGenerator = graphGenerator;
    }

    @Override
    public @NonNull List<PlatformEvent> provideEvents(final int numberOfEvents) {
        return graphGenerator.generateEvents(numberOfEvents).stream()
                .map(EventImpl::getBaseEvent)
                .toList();
    }

    @Override
    public void reset() {
        graphGenerator.reset();
    }
}
