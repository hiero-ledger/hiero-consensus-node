// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.test.fixtures.consensus.framework.TestInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Random;
import org.assertj.core.api.ThrowingConsumer;

public class ConsensusTestRunner {
    private ConsensusTestParams params;
    private List<PlatformContext> contexts;
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

    public @NonNull ConsensusTestRunner setContexts(@NonNull final List<PlatformContext> contexts) {
        this.contexts = contexts;
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
            for (final PlatformContext context : contexts) {
                test.accept(
                        new TestInput(context, params.numNodes(), params.weightGenerator(), seed, eventsToGenerate));
            }
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
