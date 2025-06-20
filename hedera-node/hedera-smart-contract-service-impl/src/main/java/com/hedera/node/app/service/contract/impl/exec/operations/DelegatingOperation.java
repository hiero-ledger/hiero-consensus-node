// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.operation.Operation;

/**
 * A delegating operation that delegates everything; used to eliminate duplication between
 * {@link CustomSStoreOperation} and {@link CustomSLoadOperation}.
 */
public class DelegatingOperation implements Operation {
    private final Operation delegate;

    /**
     * @param delegate the given operation
     */
    public DelegatingOperation(@NonNull final Operation delegate) {
        this.delegate = delegate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OperationResult execute(MessageFrame frame, EVM evm) {
        return delegate.execute(frame, evm);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOpcode() {
        return delegate.getOpcode();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return delegate.getName();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStackItemsConsumed() {
        return delegate.getStackItemsConsumed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getStackItemsProduced() {
        return delegate.getStackItemsProduced();
    }
}
