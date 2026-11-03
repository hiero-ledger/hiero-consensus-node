// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.consensus.benchmark.tools.event.EmpiricalDistribution.constant;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Checks that {@link EventShape#sampleTransactions(Random)} draws the number and sizes of the shape, the same
 * transactions for the same seed, and rejects shapes that draw impossible transactions.
 */
class EventShapeTest {

    private static final long SEED = 42;

    /** A mix of transaction counts and sizes. */
    private static final EventShape MIXED = new EventShape(
            constant(1),
            EmpiricalDistribution.of(new long[] {0, 1, 5}, new double[] {5, 3, 2}),
            EmpiricalDistribution.of(new long[] {1, 445, 6_144}, new double[] {1, 8, 1}));

    @Test
    void transactionsFollowTheShape() {
        final EventShape shape = new EventShape(constant(1), constant(3), constant(445));

        assertThat(shape.sampleTransactions(new Random(SEED)))
                .hasSize(3)
                .allSatisfy(transaction -> assertThat(transaction.length()).isEqualTo(445));
    }

    @Test
    void sameSeedGivesSameTransactions() {
        final Random first = new Random(SEED);
        final Random second = new Random(SEED);

        for (int i = 0; i < 100; i++) {
            assertThat(MIXED.sampleTransactions(first)).containsExactlyElementsOf(MIXED.sampleTransactions(second));
        }
    }

    @Test
    void negativeNumberOfTransactionsIsRejected() {
        final EventShape shape = new EventShape(constant(1), constant(-1), constant(445));

        assertThatThrownBy(() -> shape.sampleTransactions(new Random(SEED)));
    }

    @Test
    void emptyTransactionIsRejected() {
        final EventShape shape = new EventShape(constant(1), constant(1), constant(-1));

        assertThatThrownBy(() -> shape.sampleTransactions(new Random(SEED)));
    }
}
