// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.token.NodePayments;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Default writable implementation for node payments.
 */
public class WritableNodePaymentsStore extends ReadableNodePaymentsStoreImpl {

    /**
     * The underlying data storage class that holds staking reward data for all nodes.
     */
    private final WritableSingletonState<NodePayments> nodePaymentsState;

    /**
     * Create a new {@link WritableNodePaymentsStore} instance.
     *
     * @param states The state to use.
     */
    public WritableNodePaymentsStore(@NonNull final WritableStates states) {
        super(states);
        this.nodePaymentsState = requireNonNull(states).getSingleton(NODE_PAYMENTS_STATE_ID);
    }

    /**
     * Persists the node payments data to the underlying storage.
     *
     * @param nodePayments The node payments data to persist.
     */
    public void put(@NonNull final NodePayments nodePayments) {
        requireNonNull(nodePayments);
        nodePaymentsState.put(nodePayments);
    }

    /**
     * Resets the node rewards state for a new payment period.
     */
    public void resetForNewStakingPeriod(Timestamp lastNodeFeeDistributionTime) {
        nodePaymentsState.put(NodePayments.newBuilder()
                .payments(List.of())
                .lastNodeFeeDistributionTime(lastNodeFeeDistributionTime)
                .build());
    }
}
