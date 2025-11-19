// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.Node;

/**
 * Interface representing a connection between two nodes in a network.
 */
public interface BidirectionalConnection extends Connection {

    /**
     * Gets the first node of the connection.
     *
     * @return the first node in the connection
     */
    @NonNull
    Node node1();

    /**
     * Gets the second node of the connection.
     *
     * @return the second node in the connection
     */
    @NonNull
    Node node2();

    /**
     * {@inheritDoc}
     *
     * <p>If the latency range between both directions differs, the larger latency is returned.
     */
    @Override
    @NonNull
    Duration latency();

    /**
     * {@inheritDoc}
     *
     * <p>If the jitter percentage between both directions differs, the larger jitter is returned.
     */
    @Override
    @NonNull
    Percentage jitter();

    /**
     * {@inheritDoc}
     *
     * <p>If the bandwidth limit between both directions differs, the smaller bandwidth is returned.
     */
    @Override
    @NonNull
    BandwidthLimit bandwidthLimit();
}
