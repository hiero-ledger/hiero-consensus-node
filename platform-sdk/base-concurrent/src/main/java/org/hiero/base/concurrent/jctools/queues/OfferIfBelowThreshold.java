// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.jctools.queues;

import org.hiero.base.concurrent.jctools.util.InternalAPI;

/**
 * This is internal API for ease of generic testing
 */
@InternalAPI
public interface OfferIfBelowThreshold<E> {
    boolean offerIfBelowThreshold(final E e, int threshold);
}
