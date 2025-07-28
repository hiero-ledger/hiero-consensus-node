// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents the result of any reconnect operations a single node may have performed in the Otter framework. This
 * interface extends {@link OtterResult} and provides methods to retrieve the number of successful and failed
 * reconnects.
 */
public interface SingleNodeReconnectResult extends OtterResult {

    /**
     * Returns the node ID of the results' node
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the number of successful reconnects performed by the node.
     *
     * @return the number of successful reconnects
     */
    int numSuccessfulReconnects();

    /**
     * Returns the number of reconnects performed by the node that failed.
     *
     * @return the number of failed reconnects
     */
    int numFailedReconnects();
}
