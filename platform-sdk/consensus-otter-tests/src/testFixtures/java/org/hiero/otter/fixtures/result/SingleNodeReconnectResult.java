// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.logging.StructuredLog;

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

    /**
     * Subscribes to the {@link ReconnectStartPayload} log payloads for this node.
     *
     * @param subscriber the subscriber to be notified of reconnect start payloads
     */
    void subscribe(@NonNull ReconnectStartPayloadSubscriber subscriber);

    /**
     * Subscribes to the {@link ReconnectFailurePayload} log payloads for this node.
     *
     * @param subscriber the subscriber to be notified of reconnect failure payloads
     */
    void subscribe(@NonNull ReconnectFailurePayloadSubscriber subscriber);

    /**
     * Subscribes to the {@link SynchronizationCompletePayload} log payloads for this node.
     *
     * @param subscriber the subscriber to be notified of reconnect failure payloads
     */
    void subscribe(@NonNull SynchronizationCompletePayloadSubscriber subscriber);

    /**
     * Returns a list of {@link SynchronizationCompletePayload} entries logged by the node.
     *
     * <p>This method retrieves all synchronization complete payloads that have been logged by the node.</p>
     *
     * @return a list of synchronization complete payloads
     */
    @NonNull
    List<SynchronizationCompletePayload> getSynchronizationCompletePayloads();
}
