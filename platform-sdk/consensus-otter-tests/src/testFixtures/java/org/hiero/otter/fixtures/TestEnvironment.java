package org.hiero.otter.fixtures;

public interface TestEnvironment {
    Network network();

    TimeManager timeManager();

    EventGenerator generator();

    Validator validator();
}
