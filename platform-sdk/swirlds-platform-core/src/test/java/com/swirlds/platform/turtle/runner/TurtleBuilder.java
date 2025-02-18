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

package com.swirlds.platform.turtle.runner;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Configures and builds a Turtle.
 * <pre>
 *    _________________                        _________________
 *  /   Testing        \                     /        gnitseT   \
 * |    Utility         |                   |         ytilitU    |
 * |    Running         |    _ -     - _    |         gninnuR    |
 * |    Totally in a    |=<( o 0 ) ( o O )<=|    a ni yllatoT    |
 * |    Local           |   \===/   \===/   |           lacoL    |
 *  \   Environment    /                     \    tnemnorivnE    /
 *   ------------------                        ------------------
 *   / /       | | \ \                          / / | |       \ \
 *  """        """ """                         """  """        """
 * </pre>
 */
public class TurtleBuilder {

    private final Randotron randotron;
    private Duration simulationGranularity = Duration.ofMillis(10);
    private int nodeCount = 4;
    private boolean timeReportingEnabled;
    private Path outputDirectory;

    /**
     * Create a new TurtleBuilder.
     *
     * @param randotron a source of randomness
     * @return a new TurtleBuilder
     */
    public static TurtleBuilder create(@NonNull final Randotron randotron) {
        return new TurtleBuilder(randotron);
    }

    /**
     * Constructor.
     *
     * @param randotron a source of randomness
     */
    private TurtleBuilder(@NonNull final Randotron randotron) {
        this.randotron = Objects.requireNonNull(randotron);
    }

    @NonNull
    public Turtle build() {
        return new Turtle(this);
    }

    /**
     * Set the simulation granularity.
     *
     * @param simulationGranularity the simulation granularity
     * @return this builder
     */
    @NonNull
    public TurtleBuilder withSimulationGranularity(@NonNull final Duration simulationGranularity) {
        this.simulationGranularity = Objects.requireNonNull(simulationGranularity);
        if (simulationGranularity.isZero() || simulationGranularity.isNegative()) {
            throw new IllegalArgumentException("simulation granularity must be a positive non-zero value");
        }
        return this;
    }

    /**
     * Get the simulation granularity.
     *
     * @return the simulation granularity
     */
    @NonNull
    Duration getSimulationGranularity() {
        return simulationGranularity;
    }

    /**
     * Set the number of nodes to run.
     *
     * @param nodeCount the number of nodes to run
     * @return this builder
     */
    @NonNull
    public TurtleBuilder withNodeCount(final int nodeCount) {
        if (nodeCount < 1) {
            throw new IllegalArgumentException("node count must be at least 1");
        }
        this.nodeCount = nodeCount;
        return this;
    }

    /**
     * Get the number of nodes to run.
     *
     * @return the number of nodes to run
     */
    int getNodeCount() {
        return nodeCount;
    }

    /**
     * Get the randotron.
     *
     * @return the randotron
     */
    @NonNull
    Randotron getRandotron() {
        return randotron;
    }

    /**
     * Enable or disable time reporting to the console.
     *
     * @param timeReportingEnabled true to enable time reporting, false to disable
     * @return this builder
     */
    @NonNull
    public TurtleBuilder withTimeReportingEnabled(final boolean timeReportingEnabled) {
        this.timeReportingEnabled = timeReportingEnabled;
        return this;
    }

    /**
     * Set node output directory.
     *
     * @param outputDirectory the directory where node output will be stored, like saved state and so on
     * @return this builder
     */
    @NonNull
    public TurtleBuilder withOutputDirectory(@NonNull final Path outputDirectory) {
        this.outputDirectory = outputDirectory;
        return this;
    }

    /**
     * Get node output directory.
     *
     * @return the directory where the node output will be stored, like saved state and so on
     */
    @NonNull
    Path getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Get whether time reporting is enabled.
     *
     * @return true if time reporting is enabled, false otherwise
     */
    boolean isTimeReportingEnabled() {
        return timeReportingEnabled;
    }
}
