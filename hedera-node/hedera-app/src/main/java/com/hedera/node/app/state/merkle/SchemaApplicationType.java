// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import com.swirlds.state.State;
import com.swirlds.state.lifecycle.Schema;

/**
 * Enumerates the ways the {@link MerkleSchemaRegistry} may apply a {@link Schema}
 * to the {@link State}.
 */
public enum SchemaApplicationType {
    /**
     * A schema contributes its state definitions (and removals) to the {@link State} every time
     * the state is initialized, independent of the version of the deserialized state and of the
     * software version; the only schemas that skip this are those that neither create nor remove
     * any state.
     */
    STATE_DEFINITIONS,
    /**
     * A schema may provide restart logic when it is the latest schema
     * available for a service, and hence able to target any final steps the
     * current software version needs in the startup phase of the node.
     */
    RESTART,
}
