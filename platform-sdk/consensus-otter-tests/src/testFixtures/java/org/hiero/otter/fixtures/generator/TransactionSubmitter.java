// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.generator;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * A {@link TransactionSubmitter} provides a method to submit transactions to a node. The implementation
 * depends on the environment.
 */
public interface TransactionSubmitter {

    /**
     * Submits a transaction to the given node.
     *
     * @param nodeId the node to which the transaction will be submitted
     * @param payload the transaction payload
     */
    void submitTransaction(@NonNull NodeId nodeId, @NonNull Bytes payload);
}
