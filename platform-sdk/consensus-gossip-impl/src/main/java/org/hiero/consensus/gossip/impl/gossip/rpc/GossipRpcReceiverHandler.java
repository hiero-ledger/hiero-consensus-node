// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip.rpc;

/**
 * Handler for messages/RPC coming from remote endpoint during gossip exchange (see {@link GossipRpcReceiver}, extended
 * with functionality for initiating periodic actions from caller thread and doing a cleanup of internal state in case
 * of global failures.
 */
public interface GossipRpcReceiverHandler extends GossipRpcReceiver {

    /**
     * Start synchronization with remote side, if all checks are successful (things like enough time has passed since
     * last synchronization, remote side has not fallen behind etc
     *
     * @param wantToExit           set to true if for some external reasons we would like to exit sync loop
     * @param ignoreIncomingEvents we are in some kind of reduced capability state (for example caused by system being
     *                             unhealthy) and we shouldn't be processing/requesting incoming events
     * @return true if we should continue dispatching messages, false if we are in proper place to break rpc
     * conversation
     */
    boolean checkForPeriodicActions(boolean wantToExit, boolean ignoreIncomingEvents);

    /**
     * Clean all resources
     */
    void cleanup();
}
