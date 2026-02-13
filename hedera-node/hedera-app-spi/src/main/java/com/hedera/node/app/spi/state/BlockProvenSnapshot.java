// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.state;

import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents an immutable snapshot of application state that has been sealed at the end of a block.
 *
 * <p>Future iterations may expose additional metadata (e.g., block proofs, consensus timestamps) as that
 * information becomes available to services.</p>
 */
public interface BlockProvenSnapshot {
    /**
     * Returns the immutable state captured at the block boundary.
     *
     * @return the sealed {@link State}
     */
    @NonNull
    State state();
}
