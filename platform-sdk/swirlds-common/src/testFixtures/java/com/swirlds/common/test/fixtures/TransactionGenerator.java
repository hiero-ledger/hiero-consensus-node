// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import com.swirlds.platform.system.transaction.Transaction;
import com.swirlds.platform.system.transaction.TransactionWrapper;
import java.util.Random;

/**
 * Generates {@link Transaction}s using the provided randomization source.
 */
@FunctionalInterface
public interface TransactionGenerator {

    /**
     * Generate an array of transactions.
     *
     * @param random
     * 		source of randomness. May or may not be used depending on the implementation.
     * @return an array of transactions
     */
    TransactionWrapper[] generate(final Random random);
}
