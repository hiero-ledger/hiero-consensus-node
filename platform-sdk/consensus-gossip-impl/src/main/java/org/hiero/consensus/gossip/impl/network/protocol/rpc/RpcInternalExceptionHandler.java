// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.gossip.impl.network.Connection;
import org.hiero.consensus.gossip.impl.network.NetworkUtils;

/**
 * Handler for exceptions happening inside rpc sync handling. Normally redirected to
 * {@link NetworkUtils#handleNetworkException}
 */
public interface RpcInternalExceptionHandler {
    /**
     * Handle the exception
     *
     * @param e          exception to handle
     * @param connection connection for which exception happened
     * @throws InterruptedException if thread was interrupted
     */
    void handleNetworkException(@NonNull Exception e, @NonNull Connection connection) throws InterruptedException;
}
