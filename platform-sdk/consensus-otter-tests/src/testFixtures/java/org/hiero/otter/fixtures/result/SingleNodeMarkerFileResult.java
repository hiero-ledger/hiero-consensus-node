// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link SingleNodeMarkerFileResult} provides information about the existence of marker files on a single node.
 */
public interface SingleNodeMarkerFileResult {

    /**
     * Returns the node ID of the node
     *
     * @return the node ID
     */
    @NonNull
    NodeId nodeId();

    /**
     * Returns the status of the marker files.
     *
     * @return the status of the marker files
     */
    @NonNull
    MarkerFilesStatus status();

    /**
     * Subscribes to marker file changes of the node.
     *
     * <p>The subscriber will be notified every time the node writes a new marker file.
     *
     * @param subscriber the subscriber that will receive the marker file updates
     */
    void subscribe(@NonNull MarkerFileSubscriber subscriber);
}
