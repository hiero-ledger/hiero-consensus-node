// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network;

import com.swirlds.platform.gossip.sync.SyncInputStream;
import com.swirlds.platform.gossip.sync.SyncOutputStream;
import java.io.IOException;
import java.net.SocketException;
import org.hiero.consensus.model.node.NodeId;

/**
 * A connection between two nodes for the purposes of syncing.
 */
public interface Connection {

    /**
     * Close this connection
     */
    void disconnect();

    /**
     * Returns the {@link NodeId} of this node.
     *
     * @return this node's ID
     */
    NodeId getSelfId();

    /**
     * Returns the {@link NodeId} of the node this connection is connected to.
     *
     * @return the connected node's ID
     */
    NodeId getOtherId();

    /**
     * The input stream used to receive data from the connected node.
     *
     * @return the input stream
     */
    SyncInputStream getDis();

    /**
     * The output stream used to send data to the connected node.
     *
     * @return the output stream
     */
    SyncOutputStream getDos();

    /**
     * Is there currently a valid connection from me to the member at the given index in the address book?
     *
     * If this method returns {@code true}, the underlying socket is guaranteed to be non-null.
     *
     * @return true is connected, false otherwise.
     */
    boolean connected();

    /**
     * Returns the current timeout value of this connection.
     *
     * @return the current timeout value in milliseconds
     * @throws SocketException
     * 		if there is an error in the underlying protocol, such as a TCP error.
     */
    int getTimeout() throws SocketException;

    /**
     * Sets the timeout of this connection.
     *
     * @param timeoutMillis
     * 		The timeout value to set in milliseconds. A value of zero is treated as an infinite timeout.
     * @throws SocketException
     * 		if there is an error in the underlying protocol, such as a TCP error.
     */
    void setTimeout(final long timeoutMillis) throws SocketException;

    /**
     * Initialize {@code this} instance for a gossip session.
     *
     * @throws IOException
     * 		if the connection is broken
     */
    void initForSync() throws IOException;

    /**
     * Is this an outbound or an inbound connection. For outbound connections, we initiate the creation of the
     * connection, as well as all communication (sync, heartbeat, reconnect). The reverse is true for inbound
     * connections.
     *
     * @return true if this is an outbound connection, false otherwise
     */
    boolean isOutbound();

    /**
     * @return a string description of this connection
     */
    String getDescription();

    /**
     * Generate a default connection description
     *
     * @return the description of the connection
     */
    default String generateDescription() {
        return String.format("%s %s %s", getSelfId(), isOutbound() ? "->" : "<-", getOtherId());
    }
}
