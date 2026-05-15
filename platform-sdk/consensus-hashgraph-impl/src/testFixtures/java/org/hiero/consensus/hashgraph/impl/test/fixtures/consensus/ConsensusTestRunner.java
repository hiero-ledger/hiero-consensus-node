// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.assertj.core.api.ThrowingConsumer;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.TestInput;
import org.hiero.consensus.metrics.noop.NoOpMetrics;

public class ConsensusTestRunner {
    private final Metrics metrics = new NoOpMetrics();
    private final Time time = Time.getCurrent();

    private ConsensusTestParams params;
    private Configuration configuration;
    private ThrowingConsumer<TestInput> test;
    private int iterations = 1;
    private int eventsToGenerate = 10_000;

    public static @NonNull ConsensusTestRunner create() {
        return new ConsensusTestRunner();
    }

    public @NonNull ConsensusTestRunner setParams(@NonNull final ConsensusTestParams params) {
        this.params = params;
        return this;
    }

    public @NonNull ConsensusTestRunner setConfiguration(@NonNull final Configuration configuration) {
        this.configuration = configuration;
        return this;
    }

    public @NonNull ConsensusTestRunner setTest(@NonNull final ThrowingConsumer<TestInput> test) {
        this.test = test;
        return this;
    }

    public @NonNull ConsensusTestRunner setIterations(final int iterations) {
        this.iterations = iterations;
        return this;
    }

    public void run() {
        for (final long seed : params.seeds()) {
            runWithSeed(seed);
        }

        if (params.seeds().length > 0) {
            // if we are given an explicit seed, we should not run with random seeds
            return;
        }

        for (int i = 0; i < iterations; i++) {
            final long seed = new Random().nextLong();
            runWithSeed(seed);
        }
    }

    private void runWithSeed(final long seed) {
        System.out.println("Running seed: " + seed);
        try {
            test.accept(new TestInput(
                    configuration, metrics, time, params.numNodes(), params.weightGenerator(), seed, eventsToGenerate));
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
