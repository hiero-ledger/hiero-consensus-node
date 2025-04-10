// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

public interface Distributor {

    /**
     * Create a random transaction and distribute it to one of the network's nodes.
     */
    void submitTransaction();
}
