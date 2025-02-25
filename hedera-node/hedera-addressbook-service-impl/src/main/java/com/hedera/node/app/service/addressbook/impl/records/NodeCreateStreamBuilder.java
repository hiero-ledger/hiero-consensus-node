// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code NodeCreate} transaction.
 */
public interface NodeCreateStreamBuilder extends StreamBuilder {
    /**
     * Tracks creation of a new node by nodeID.
     *
     * @param nodeID  the new node
     * @return this builder
     */
    @NonNull
    NodeCreateStreamBuilder nodeID(long nodeID);
}
