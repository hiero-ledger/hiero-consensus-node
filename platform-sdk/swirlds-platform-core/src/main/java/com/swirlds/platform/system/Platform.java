// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * An interface for Swirlds Platform.
 */
public interface Platform {

    /**
     * Get the platform context, which contains various utilities and services provided by the platform.
     *
     * @return this node's platform context
     */
    @NonNull
    PlatformContext getContext();

    /**
     * Get the notification engine running on this node.
     *
     * @return a notification engine
     */
    @NonNull
    NotificationEngine getNotificationEngine();

    /**
     * Get the Roster
     *
     * @return the roster
     */
    @NonNull
    Roster getRoster();

    /**
     * Get the ID of current node
     *
     * @return node ID
     */
    @NonNull
    NodeId getSelfId();

    /**
     * generate signature bytes for given data
     *
     * @param data
     * 		an array of bytes
     * @return signature bytes
     */
    @NonNull
    Signature sign(@NonNull byte[] data);

    /**
     * Instruct the platform on what its quiescence state should be. The platform will use the latest command that has
     * been provided. If multiple threads call this method at the same time, there is no guarantee about which command
     * will be used.
     *
     * @param quiescenceCommand the quiescence command
     */
    void quiescenceCommand(@NonNull QuiescenceCommand quiescenceCommand);

    /**
     * Start this platform.
     */
    void start();

    /**
     * Destroy this platform and release all resources. Once this method is called, the platform cannot be used again.
     *
     * @throws InterruptedException if the thread is interrupted while waiting for the platform to shut down
     */
    void destroy() throws InterruptedException;
}
