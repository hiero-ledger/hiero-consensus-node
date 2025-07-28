// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * Interface that provides access to the status progression results of a node.
 *
 * <p>The provided data is a snapshot of the state at the moment when the result was requested.
 */
public interface SingleNodePlatformStatusResult extends OtterResult {

    /**
     * Returns the node ID of the node which status progression has been recorded.
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the list of platform status progression created during the test.
     *
     * @return the list of platform status
     */
    @NonNull
    List<PlatformStatus> statusProgression();

    /**
     * Returns the current platform status of the node.
     *
     * <p>This is the last status in the progression list.
     *
     * @return the current platform status, or {@code null} if no status has been recorded
     */
    @Nullable
    PlatformStatus currentStatus();

    /**
     * Subscribes to {@link PlatformStatus} changes a node goes through.
     *
     * <p>The subscriber will be notified every time the status of the node changes.
     *
     * @param subscriber the subscriber that will receive the new status
     */
    void subscribe(@NonNull PlatformStatusSubscriber subscriber);
}
