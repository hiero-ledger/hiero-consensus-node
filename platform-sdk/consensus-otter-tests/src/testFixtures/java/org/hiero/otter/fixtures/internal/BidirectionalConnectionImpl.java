// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.assertj.core.data.Percentage;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.BidirectionalConnection;
import org.hiero.otter.fixtures.network.UnidirectionalConnection;

/**
 * Implementation of the BidirectionalConnection interface.
 */
public class BidirectionalConnectionImpl implements BidirectionalConnection {

    private final UnidirectionalConnection connection;
    private final UnidirectionalConnection reverse;

    /**
     * Constructs a BidirectionalConnectionImpl with the specified one-way connections.
     *
     * @param connection the one-way connection from node1 to node2
     * @param reverse    the one-way connection from node2 to node1
     * @throws NullPointerException if any of the parameters are null
     */
    public BidirectionalConnectionImpl(
            @NonNull final UnidirectionalConnection connection, @NonNull final UnidirectionalConnection reverse) {
        this.connection = requireNonNull(connection);
        this.reverse = requireNonNull(reverse);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Node node1() {
        return connection.sender();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Node node2() {
        return connection.receiver();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void disconnect() {
        connection.disconnect();
        reverse.disconnect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void connect() {
        connection.connect();
        reverse.connect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isConnected() {
        return connection.isConnected() && reverse.isConnected();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreConnectivity() {
        connection.restoreConnectivity();
        reverse.restoreConnectivity();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Duration latency() {
        final Duration latency1 = connection.latency();
        final Duration latency2 = reverse.latency();
        return latency1.compareTo(latency2) > 0 ? latency1 : latency2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void latency(@NonNull final Duration latency) {
        connection.latency(latency);
        reverse.latency(latency);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Percentage jitter() {
        final Percentage jitter1 = connection.jitter();
        final Percentage jitter2 = reverse.jitter();
        return jitter1.value > jitter2.value ? jitter1 : jitter2;
    }

    @Override
    public void jitter(@NonNull final Percentage jitter) {
        connection.jitter(jitter);
        reverse.jitter(jitter);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreLatency() {
        connection.restoreLatency();
        reverse.restoreLatency();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public BandwidthLimit bandwidthLimit() {
        final BandwidthLimit limit1 = connection.bandwidthLimit();
        final BandwidthLimit limit2 = reverse.bandwidthLimit();
        return limit1.compareTo(limit2) < 0 ? limit1 : limit2;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bandwidthLimit(@NonNull final BandwidthLimit bandwidthLimit) {
        connection.bandwidthLimit(bandwidthLimit);
        reverse.bandwidthLimit(bandwidthLimit);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void restoreBandwidthLimit() {
        connection.restoreBandwidthLimit();
        reverse.restoreBandwidthLimit();
    }
}
