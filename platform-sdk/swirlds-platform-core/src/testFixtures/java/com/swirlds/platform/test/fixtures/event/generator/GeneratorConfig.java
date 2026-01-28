package com.swirlds.platform.test.fixtures.event.generator;

/**
 * Configuration parameters for the event generator.
 *
 * @param seed             the random seed used for event generation
 * @param maxOtherParents the maximum number of other parents an event can have
 */
public record GeneratorConfig(long seed, int maxOtherParents) {
}
