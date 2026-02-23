// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.concurrent.utility.throttle.RateLimiter;
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
     * @param e           exception to handle
     * @param connection  connection for which exception happened
     * @param rateLimiter rate limiter to use for the exception
     * @throws InterruptedException if thread was interrupted
     */
    void handleNetworkException(@NonNull Exception e, @NonNull Connection connection, @NonNull RateLimiter rateLimiter)
            throws InterruptedException;
}
