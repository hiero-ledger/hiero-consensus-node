// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import org.hiero.otter.fixtures.TransactionGenerator;

/**
 * A transaction generator that generates nothing. Falcon ignores transactions entirely.
 */
public class FalconTransactionGenerator implements TransactionGenerator {

    @Override
    public void start() {
        // no-op
    }

    @Override
    public void stop() {
        // no-op
    }
}
