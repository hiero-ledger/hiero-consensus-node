package org.hiero.otter.fixtures;

import java.time.Duration;

public interface TimeManager {
    void waitFor(Duration waitTime);

    void waitForEvents(int eventCount);
}
