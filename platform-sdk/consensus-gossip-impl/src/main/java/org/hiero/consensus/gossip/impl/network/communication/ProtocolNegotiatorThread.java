// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.communication;

import com.swirlds.base.time.Time;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.hiero.base.concurrent.interrupt.InterruptableRunnable;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.ConnectionManager;
import org.hiero.consensus.gossip.impl.network.NetworkProtocolException;
import org.hiero.consensus.gossip.impl.network.NetworkUtils;
import org.hiero.consensus.gossip.impl.network.protocol.ProtocolRunnable;

/**
 * Continuously runs protocol negotiation and protocols over connections supplied by the connection manager
 */
public class ProtocolNegotiatorThread implements InterruptableRunnable {
    /** A duration between reporting full stack traces for socket exceptions. */
    private static final Duration SOCKET_EXCEPTION_DURATION = Duration.ofMinutes(1);

    /**
     * The number of milliseconds to sleep if a negotiation fails
     */
    private final int sleepMillis;

    private final ConnectionManager connectionManager;
    private final List<ProtocolRunnable> handshakeProtocols;
    private final NegotiationProtocols protocols;
    private final RateLimiter socketExceptionRateLimiter;

    /**
     * @param connectionManager  supplies network connections
     * @param sleepMillis        the number of milliseconds to sleep if a negotiation fails
     * @param handshakeProtocols the list of protocols to execute when a new connection is established
     * @param protocols          the protocols to negotiate and run
     * @param time               the Time object
     */
    public ProtocolNegotiatorThread(
            final ConnectionManager connectionManager,
            final int sleepMillis,
            final List<ProtocolRunnable> handshakeProtocols,
            final NegotiationProtocols protocols,
            final Time time) {

        this.connectionManager = connectionManager;
        this.sleepMillis = sleepMillis;
        this.handshakeProtocols = handshakeProtocols;
        this.protocols = protocols;
        this.socketExceptionRateLimiter = new RateLimiter(time, SOCKET_EXCEPTION_DURATION);
    }

    @Override
    public void run() throws InterruptedException {
        final Connection currentConn = connectionManager.waitForConnection();
        final Negotiator negotiator = new Negotiator(protocols, currentConn, sleepMillis);
        try {
            // run the handshake protocols on every new connection
            for (final ProtocolRunnable handshakeProtocol : handshakeProtocols) {
                handshakeProtocol.runProtocol(currentConn);
            }

            while (currentConn.connected()) {
                negotiator.execute();
            }
        } catch (final RuntimeException | IOException | NetworkProtocolException | NegotiationException e) {
            NetworkUtils.handleNetworkException(e, currentConn, socketExceptionRateLimiter);
        }
    }
}
