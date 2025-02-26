// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.swirlds.base.time.Time;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * An adapter for {@link MerkleNodeState} that delegates all calls to the wrapped instance.
 */
@ConstructableIgnored
public class MerkleNodeStateAdapter implements MerkleNodeState {

    private final MerkleNodeState delegate;

    public MerkleNodeStateAdapter(MerkleNodeState delegate) {
        this.delegate = delegate;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public MerkleNodeStateAdapter copy() {
        return new MerkleNodeStateAdapter(delegate.copy());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends MerkleNode> void putServiceStateIfAbsent(
            @NonNull StateMetadata<?, ?> md, @NonNull Supplier<T> nodeSupplier, @NonNull Consumer<T> nodeInitializer) {
        delegate.putServiceStateIfAbsent(md, nodeSupplier, nodeInitializer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unregisterService(@NonNull String serviceName) {
        delegate.unregisterService(serviceName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeServiceState(@NonNull String serviceName, @NonNull String stateKey) {
        delegate.removeServiceState(serviceName, stateKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release() {
        return delegate.release();
    }

    @Override
    public boolean isDestroyed() {
        return delegate.isDestroyed();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(Time time, Metrics metrics, MerkleCryptography merkleCryptography, LongSupplier roundSupplier) {
        delegate.init(time, metrics, merkleCryptography, roundSupplier);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReadableStates getReadableStates(@NonNull String serviceName) {
        return delegate.getReadableStates(serviceName);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public WritableStates getWritableStates(@NonNull String serviceName) {
        return delegate.getWritableStates(serviceName);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setHash(Hash hash) {
        delegate.setHash(hash);
    }
}
