// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NodePayments;
import com.hedera.node.app.service.token.ReadableNodePaymentsStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link ReadableNodePaymentsStore}.
 */
public class ReadableNodePaymentsStoreImpl implements ReadableNodePaymentsStore {

    /**
     * The underlying data storage class that holds node payments data for all nodes.
     */
    private final ReadableSingletonState<NodePayments> nodePaymentsState;

    /**
     * Create a new {@link ReadableNodePaymentsStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableNodePaymentsStoreImpl(@NonNull final ReadableStates states) {
        this.nodePaymentsState = requireNonNull(states).getSingleton(NODE_PAYMENTS_STATE_ID);
    }

    @Override
    public NodePayments get() {
        return requireNonNull(nodePaymentsState.get());
    }
}
