// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

/**
 * Describes the reason why state was rebuilt and is now being initialized.
 */
public enum InitTrigger {
    /**
     * The state was created because this node is starting at genesis.
     */
    GENESIS,
    /**
     * The state was created because this node is restarting from a saved state.
     */
    RESTART,
    /**
     * The state was created because this node had to reconnect.
     */
    RECONNECT,
    /**
     * The state was created to be used in a recovery workflow (event stream or block stream).
     */
    RECOVERY
}
