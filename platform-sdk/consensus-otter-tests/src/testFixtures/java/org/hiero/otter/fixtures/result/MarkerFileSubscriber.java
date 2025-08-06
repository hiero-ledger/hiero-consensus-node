// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Defines a subscriber that will receive an update every time a node writes a new marker file.
 */
@FunctionalInterface
public interface MarkerFileSubscriber {

    /**
     * Called when a node writes a new marker file.
     *
     * @param nodeId the node that wrote the marker file
     * @param status the status of the marker files
     * @return {@link SubscriberAction#UNSUBSCRIBE} to unsubscribe, {@link SubscriberAction#CONTINUE} to continue
     */
    SubscriberAction onNewMarkerFile(@NonNull NodeId nodeId, @NonNull MarkerFilesStatus status);
}
