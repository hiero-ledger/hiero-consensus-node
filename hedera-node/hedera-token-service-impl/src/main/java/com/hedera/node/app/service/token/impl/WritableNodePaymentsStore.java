// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.schemas.V0700TokenSchema.NODE_PAYMENTS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.token.NodePayment;
import com.hedera.hapi.node.state.token.NodePayments;
import com.hederahashgraph.api.proto.java.Node;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

import java.util.HashMap;
import java.util.Map;

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
     * Resets the node payments state for a new staking period.
     */
    public void resetForNewStakingPeriod() {
        nodePaymentsState.put(NodePayments.newBuilder().payments(new HashMap<Long, NodePayment>()).build());
    }

    public void addNodePayments(final long accountNumber, final long amount) {
        final var nodePayments = requireNonNull(nodePaymentsState.get());
        final var oldPayment = nodePayments.payments().get(accountNumber);
        final var oldFees = oldPayment == null ? 0 : oldPayment.fees();
        final var payment = NodePayment.newBuilder()
                .accountNumber(accountNumber)
                .fees(oldFees + amount)
                .build();
        final var payments = nodePayments.payments();
        payments.put(accountNumber, payment);
        nodePaymentsState.put(NodePayments.newBuilder().payments(payments).build());
    }
}
