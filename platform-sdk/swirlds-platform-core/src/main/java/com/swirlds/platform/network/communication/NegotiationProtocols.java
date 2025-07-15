// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.communication;

import com.swirlds.platform.network.protocol.PeerProtocol;
import java.util.List;

/**
 * Manages protocols during a protocol negotiation
 */
public class NegotiationProtocols {
    private final PeerProtocol[] allPeerProtocols;
    /** the protocol initiated, if any */
    private PeerProtocol initiatedPeerProtocol = null;

    /**
     * @param peerProtocols
     * 		a list of protocols to negotiate in order of priority
     */
    public NegotiationProtocols(final List<PeerProtocol> peerProtocols) {
        if (peerProtocols == null
                || peerProtocols.isEmpty()
                || peerProtocols.size() > NegotiatorBytes.MAX_NUMBER_OF_PROTOCOLS) {
            throw new IllegalArgumentException(
                    "the list of protocols supplied should be: non-null, non-empty and have a size lower than "
                            + NegotiatorBytes.MAX_NUMBER_OF_PROTOCOLS);
        }
        this.allPeerProtocols = peerProtocols.toArray(new PeerProtocol[0]);
    }

    /**
     * Get the protocol with the supplied ID
     *
     * @param id
     * 		the ID of the protocol
     * @return the protocol requested
     * @throws NegotiationException
     * 		if an invalid ID is supplied
     */
    public PeerProtocol getProtocol(final int id) throws NegotiationException {
        if (id < 0 || id >= allPeerProtocols.length) {
            throw new NegotiationException("not a valid protocol ID: " + id);
        }
        return allPeerProtocols[id];
    }

    /**
     * The protocol initiated has been accepted
     *
     * @return the protocol initiated
     * @throws IllegalStateException
     * 		if no protocol was previously initiated
     */
    public PeerProtocol initiateAccepted() {
        throwIfNoneInitiated();
        final PeerProtocol ret = initiatedPeerProtocol;
        initiatedPeerProtocol = null;
        return ret;
    }

    /**
     * The protocol initiated has not been accepted
     *
     * @throws IllegalStateException
     * 		if no protocol was previously initiated
     */
    public void initiateFailed() {
        throwIfNoneInitiated();
        initiatedPeerProtocol.initiateFailed();
        initiatedPeerProtocol = null;
    }

    /**
     * If a negotiation exception occurred, we must notify any initiated protocol that the initiate failed
     */
    public void negotiationExceptionOccurred() {
        if (initiatedPeerProtocol != null) {
            initiatedPeerProtocol.initiateFailed();
        }
    }

    /**
     * @return the protocol that was initiated by self
     * @throws IllegalStateException
     * 		if no protocol was previously initiated
     */
    public PeerProtocol getInitiatedProtocol() {
        throwIfNoneInitiated();
        return initiatedPeerProtocol;
    }

    /**
     * @return the ID of the protocol that should be initiated, or -1 if none
     */
    public byte initiateProtocol() {
        // check each protocol in order of priority until we find one we should initiate
        for (byte i = 0; i < allPeerProtocols.length; i++) {
            if (allPeerProtocols[i].shouldInitiate()) {
                initiatedPeerProtocol = allPeerProtocols[i];
                return i;
            }
        }
        return -1;
    }

    private void throwIfNoneInitiated() {
        if (initiatedPeerProtocol == null) {
            throw new IllegalStateException("no protocol initiated");
        }
    }

    /**
     * Perform optional cleanup on all peer protocols
     */
    public void cleanup() {
        for (final PeerProtocol peerProtocol : allPeerProtocols) {
            peerProtocol.cleanup();
        }
    }
}
