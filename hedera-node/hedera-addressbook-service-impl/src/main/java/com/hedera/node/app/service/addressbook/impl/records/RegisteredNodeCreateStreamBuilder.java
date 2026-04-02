// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.records;

import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@code StreamBuilder} specialization for tracking the side effects of a {@code RegisteredNodeCreate} transaction.
 */
public interface RegisteredNodeCreateStreamBuilder extends StreamBuilder {
    /**
     * Tracks creation of a new registered node by id.
     *
     * @param registeredNodeID the new registered node id
     * @return this builder
     */
    @NonNull
    RegisteredNodeCreateStreamBuilder registeredNodeID(long registeredNodeID);
}
