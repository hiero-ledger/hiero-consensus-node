package com.swirlds.platform.test.fixtures.gui;

import com.swirlds.platform.test.fixtures.event.generator.SimpleGraphGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.event.PlatformEvent;

public class SimpleGeneratorProvider implements GuiEventProvider{
    private final SimpleGraphGenerator generator;

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
        throw  new UnsupportedOperationException();
    }
}
