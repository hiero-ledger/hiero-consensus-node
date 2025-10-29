// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
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
     * Get the most recent immutable state. This state may or may not be hashed when it is returned. Wrapper must be
     * closed when use of the state is no longer needed else resources may be leaked.
     *
     * @param reason a short description of why this SignedState is being reserved. Each location where a SignedState is
     *               reserved should attempt to use a unique reason, as this makes debugging reservation bugs easier.
     * @param <T>    the type of the state
     * @return a wrapper around the most recent immutable state
     */
    @NonNull
    <T extends State> AutoCloseableWrapper<T> getLatestImmutableState(@NonNull final String reason);

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
