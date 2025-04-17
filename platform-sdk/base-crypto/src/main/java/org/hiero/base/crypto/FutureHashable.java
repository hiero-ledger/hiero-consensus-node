// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import java.util.concurrent.Future;

/**
 * Adds an option to wait for a Hash of a Hashable object to be calculated.
 */
public interface FutureHashable extends Hashable {
    /**
     * Returns a {@link Future} which will be completed once the Hash of this Hashable object has been calculated
     *
     * @return a future linked to the Hash of this Hashable object
     */
    Future<Hash> getFutureHash();
}
