package org.hiero.otter.fixtures;

import java.time.Duration;

public interface Node {
    void kill(Duration timeout);

    void revive(Duration timeout);
}
