// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

/**
 * Fields written to the signed state metadata file.
 */
public enum SavedStateMetadataField {
    /**
     * The round of the signed state.
     */
    ROUND,
    /**
     * The root hash of the state.
     */
    HASH,
    /**
     * The root hash of the state in mnemonic form.
     */
    HASH_MNEMONIC,
    /**
     * The number of consensus events, starting from genesis, that have been handled to create this state.
     */
    NUMBER_OF_CONSENSUS_EVENTS,
    /**
     * The consensus timestamp of this state.
     */
    CONSENSUS_TIMESTAMP,
    /**
     * The legacy running event hash used by the consensus event stream.
     */
    LEGACY_RUNNING_EVENT_HASH,
    /**
     * The legacy running event hash used by the consensus event stream, in mnemonic form.
     */
    LEGACY_RUNNING_EVENT_HASH_MNEMONIC,
    /**
     * The minimum birthround of non-ancient events after this state reached consensus.
     */
    MINIMUM_BIRTH_ROUND_NON_ANCIENT,
    /**
     * The application software version that created this state.
     */
    SOFTWARE_VERSION,
    /**
     * The wall clock time when this state was written to disk.
     */
    WALL_CLOCK_TIME,
    /**
     * The ID of the node that wrote this state to disk.
     */
    NODE_ID,
    /**
     * A comma separated list of node IDs that signed this state.
     */
    SIGNING_NODES,
    /**
     * The sum of all signing nodes' weights.
     */
    SIGNING_WEIGHT_SUM,
    /**
     * The total weight of all nodes in the network.
     */
    TOTAL_WEIGHT,
    /** True if this state is a freeze state. */
    FREEZE_STATE;
}
