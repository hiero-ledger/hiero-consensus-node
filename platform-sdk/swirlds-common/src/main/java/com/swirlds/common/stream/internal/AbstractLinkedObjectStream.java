// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream.internal;

import java.util.Objects;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.crypto.RunningHashable;
import org.hiero.consensus.model.crypto.SerializableHashable;

/**
 * This abstract class implements boiler plate functionality for a {@link LinkedObjectStream}.
 *
 * @param <T>
 * 		type of the objects to be processed by this stream
 */
public abstract class AbstractLinkedObjectStream<T extends RunningHashable & SerializableHashable>
        implements LinkedObjectStream<T> {

    private LinkedObjectStream<T> nextStream;

    protected AbstractLinkedObjectStream() {}

    protected AbstractLinkedObjectStream(final LinkedObjectStream<T> nextStream) {
        this();
        this.nextStream = nextStream;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setRunningHash(final Hash hash) {
        if (nextStream != null) {
            nextStream.setRunningHash(hash);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addObject(T t) {
        if (nextStream != null) {
            nextStream.addObject(Objects.requireNonNull(t));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        if (nextStream != null) {
            nextStream.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (nextStream != null) {
            nextStream.close();
        }
    }
}
