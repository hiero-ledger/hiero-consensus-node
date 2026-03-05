// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.state.token.NodePayments;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with node payments.
 */
public interface ReadableNodePaymentsStore {
    /**
     * Returns the {link NodePayments} in state.
     *
     * @return the {link NodePayments} in state
     */
    NodePayments get();
}
