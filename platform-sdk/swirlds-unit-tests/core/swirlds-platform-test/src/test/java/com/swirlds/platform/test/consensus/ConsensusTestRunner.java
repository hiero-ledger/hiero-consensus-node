// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus;

import com.swirlds.platform.test.consensus.framework.TestInput;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.junit.jupiter.api.function.ThrowingConsumer;

public class ConsensusTestRunner {
    private ConsensusTestParams params;
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

    public @NonNull ConsensusTestRunner setTest(@NonNull final ThrowingConsumer<TestInput> test) {
        this.test = test;
        return this;
    }

    public @NonNull ConsensusTestRunner setIterations(final int iterations) {
        this.iterations = iterations;
        return this;
    }

    public void run() {
        try {
            for (final long seed : params.seeds()) {
                System.out.println("Running seed: " + seed);

                test.accept(new TestInput(
                        params.platformContext(), params.numNodes(), params.weightGenerator(), seed, eventsToGenerate));
            }

            for (int i = 0; i < iterations; i++) {
                final long seed = new Random().nextLong();
                System.out.println("Running seed: " + seed);
                test.accept(new TestInput(
                        params.platformContext(), params.numNodes(), params.weightGenerator(), seed, eventsToGenerate));
            }
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
