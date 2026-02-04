// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.gui;

import com.swirlds.platform.test.fixtures.event.generator.SimpleGraphGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * A {@link GuiEventProvider} backed by a {@link SimpleGraphGenerator}. Delegates event generation to the underlying
 * graph generator.
 */
public class SimpleGeneratorProvider implements GuiEventProvider {
    private final SimpleGraphGenerator generator;

    /**
     * Creates a new provider backed by the given generator.
     *
     * @param generator the graph generator used to produce events
     */
    public SimpleGeneratorProvider(final SimpleGraphGenerator generator) {
        this.generator = generator;
    }

    @NonNull
    @Override
    public List<PlatformEvent> provideEvents(final int numberOfEvents) {
        return generator.generateEvents(numberOfEvents);
    }

    @Override
    public void reset() {
        throw new UnsupportedOperationException();
    }
}
