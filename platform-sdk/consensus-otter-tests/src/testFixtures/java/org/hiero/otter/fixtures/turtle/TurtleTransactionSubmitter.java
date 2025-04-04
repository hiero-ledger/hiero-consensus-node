// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.generator.TransactionSubmitter;

/**
 * A {@link TransactionSubmitter} implementation for the turtle network.
 *
 * <p>This class provides a method to submit transactions to a node in the turtle network.
 */
public class TurtleTransactionSubmitter implements TransactionSubmitter {

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitTransaction(@NonNull final NodeId nodeId, @NonNull final Bytes payload) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
