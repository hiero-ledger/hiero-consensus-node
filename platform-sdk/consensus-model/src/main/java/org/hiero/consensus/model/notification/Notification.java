// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.notification;

/**
 * The base interface that must be implemented by all notifications sent to registered listeners.
 */
public interface Notification {

    /**
     * Getter that returns a unique value representing the {@link Notification} specific sequence or system-wide
     * notification order.
     *
     * @return a long value representing a unique sequence or notification order
     */
    long getSequence();

    /**
     * Setter for defining the unique value representing the {@link Notification} specific sequence or system-wide
     * notification order.
     *
     * @param id
     * 		a long value representing a unique sequence or notification order
     */
    void setSequence(final long id);
}
