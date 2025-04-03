package org.hiero.otter.fixtures;

import java.time.Duration;
import java.util.List;

public interface Network {
    List<Node> addNodes(int count);

    void start(Duration timeout);

    InstrumentedNode addInstrumentedNode();
}
