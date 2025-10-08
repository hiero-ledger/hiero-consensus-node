// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.lifecycle.Schema;

/**
 * Enumerates the ways the {@link MerkleSchemaRegistry} may apply a {@link Schema}
 * to the {@link MerkleNodeState}.
 */
public enum SchemaApplicationType {
    /**
     * A schema may contribute state definitions to the {@link MerkleNodeState}
     * no matter if it was first registered before or after the version of the
     * deserialized state. The only two conditions under which a schema {@code X}
     * need not be used for state definitions are:
     * <ol>
     *     <li>It is for a service that did not create or remove any state at
     *     all in the version at which {@code X} was registered; or,</li>
     *     <li>All its state definitions were removed by schemas
     *     registered in subsequent versions, <b>and</b> the deserialized
     *     state is missing or is no earlier than the subsequent version
     *     that removed the last of {@code X}'s states.</li>
     * </ol>
     * <b>Important:</b> In the current system there is no practical benefit to
     * doing the work to detect the second condition; so we don't.
     */
    STATE_DEFINITIONS,
    /**
     * A schema may perform migration logic from the state of an earlier
     * version; this applies at genesis or whenever the deserialized state version
     * is strictly before the version of the schema.
     */
    MIGRATION,
    /**
     * A schema may provide restart logic when it is the latest schema
     * available for a service, and hence able to target any final steps the
     * current software version needs in the startup phase of the node.
     */
    RESTART,
}
