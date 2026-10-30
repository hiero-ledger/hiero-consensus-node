// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * What the recorded nodes of a simulated network saw, made by {@link FalconRecorder}.
 *
 * @param spec what was simulated
 * @param roster the roster of the network, mini-nodes included
 * @param keys the keys and certificates of all nodes, by node ID; unused in an unsigned recording
 * @param nodes the recordings of the recorded nodes, in the order of their IDs
 */
public record Recording(
        @NonNull RecordingSpec spec,
        @NonNull RosterWrapper roster,
        @NonNull Map<NodeId, KeysAndCerts> keys,
        @NonNull List<NodeRecording> nodes) {

    /**
     * Creates a recording.
     *
     * @param spec what was simulated
     * @param roster the roster of the network, mini-nodes included
     * @param keys the keys and certificates of all nodes, by node ID
     * @param nodes the recordings of the recorded nodes, in the order of their IDs
     */
    public Recording {
        requireNonNull(spec);
        requireNonNull(roster);
        keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        nodes = List.copyOf(nodes);
    }
}
