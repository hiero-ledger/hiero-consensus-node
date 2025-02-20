// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import com.swirlds.logging.legacy.LogMarker;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import com.swirlds.platform.network.communication.states.InitialState;
import com.swirlds.platform.network.communication.states.NegotiationState;
import com.swirlds.platform.network.communication.states.ProtocolNegotiated;
import com.swirlds.platform.network.communication.states.ReceivedInitiate;
import com.swirlds.platform.network.communication.states.SentInitiate;
import com.swirlds.platform.network.communication.states.SentKeepalive;
import com.swirlds.platform.network.communication.states.Sleep;
import com.swirlds.platform.network.communication.states.WaitForAcceptReject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A state machine responsible for negotiating the protocol to run over the provided connection
 */
public class Negotiator {
    private static final Logger logger = LogManager.getLogger(Negotiator.class);
    private final NegotiationProtocols protocols;
    private final NegotiationState initialState;
    private final ProtocolNegotiated protocolNegotiated;
    private final Sleep sleep;
    private boolean errorState;
    private final String negotiatorName;

    /**
     * @param protocols
     * 		all possible protocols that could run over this connection
     * @param connection
     * 		the connection to negotiate and run the protocol on
     * @param sleepMs
     * 		the number of milliseconds to sleep if a negotiation fails
     */
    public Negotiator(final NegotiationProtocols protocols, final Connection connection, final int sleepMs) {
        this.protocols = protocols;
        protocolNegotiated = new ProtocolNegotiated(connection);
        sleep = new Sleep(sleepMs);
        final InputStream in = connection.getDis();
        final OutputStream out = connection.getDos();
        final ReceivedInitiate receivedInitiate = new ReceivedInitiate(protocols, out, protocolNegotiated, sleep);
        final WaitForAcceptReject waitForAcceptReject =
                new WaitForAcceptReject(protocols, in, protocolNegotiated, sleep);
        final SentInitiate sentInitiate =
                new SentInitiate(protocols, in, protocolNegotiated, receivedInitiate, waitForAcceptReject, sleep);
        final SentKeepalive sentKeepalive = new SentKeepalive(in, sleep, receivedInitiate);
        this.initialState = new InitialState(protocols, out, sentKeepalive, sentInitiate);
        this.errorState = false;
        this.negotiatorName = connection.getDescription();
    }

    /**
     * Execute a single cycle of protocol negotiation
     *
     * @throws NegotiationException
     * 		if an issue occurs during protocol negotiation
     * @throws NetworkProtocolException
     * 		if a protocol specific issue occurs
     * @throws IOException
     * 		if an I/O issue occurs
     * @throws InterruptedException
     * 		if the calling thread is interrupted while running the protocol
     */
    public void execute() throws InterruptedException, NegotiationException, NetworkProtocolException, IOException {
        if (errorState) {
            throw new IllegalStateException();
        }
        NegotiationState prev = null;
        NegotiationState current = initialState;
        while (current != null) {
            try {
                prev = current;
                current = current.transition();
                logger.debug(
                        LogMarker.PROTOCOL_NEGOTIATION.getMarker(),
                        "Negotiator {} last transition: {}",
                        negotiatorName,
                        prev.getLastTransitionDescription());
            } catch (final RuntimeException
                    | NegotiationException
                    | NetworkProtocolException
                    | InterruptedException
                    | IOException e) {
                errorState = true;
                protocols.negotiationExceptionOccurred();
                throw e;
            }
        }
        if (prev != sleep && prev != protocolNegotiated) {
            throw new NegotiationException("The outcome should always be sleep or running a protocol");
        }
    }
}
