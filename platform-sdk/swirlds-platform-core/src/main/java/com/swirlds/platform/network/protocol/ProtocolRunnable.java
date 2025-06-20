// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.network.protocol;

import com.swirlds.platform.network.Connection;
import com.swirlds.platform.network.NetworkProtocolException;
import java.io.IOException;

/**
 * Represents a method for running a network protocol
 */
@FunctionalInterface
public interface ProtocolRunnable {
    /**
     * Run the protocol over the provided connection. Once the protocol is done running, it should not leave any unread
     * bytes in the input stream unless an exception is thrown. This is important since the connection will be reused.
     *
     * @param connection
     * 		the connection to run the protocol on
     * @throws NetworkProtocolException
     * 		if a protocol specific issue occurs
     * @throws IOException
     * 		if an I/O issue occurs
     * @throws InterruptedException
     * 		if the calling thread is interrupted while running the protocol
     */
    void runProtocol(Connection connection) throws NetworkProtocolException, IOException, InterruptedException;
}
