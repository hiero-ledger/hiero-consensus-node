// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.notification;

/**
 * Abstract base class provided for convenience of implementing {@link Notification} classes. Provides the basic sequence
 * support as required by the {@link Notification} interface.
 */
public abstract class AbstractNotification implements Notification {

    private long sequence;

    public AbstractNotification() {}

    /**
     * {@inheritDoc}
     */
    @Override
    public long getSequence() {
        return sequence;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSequence(final long id) {
        this.sequence = id;
    }
}
