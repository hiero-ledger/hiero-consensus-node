// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Supplies the most recent block-proven snapshot made available by the platform.
 */
public interface BlockProvenSnapshotProvider {
    /**
     * Returns the latest snapshot, if one has been observed.
     *
     * @return an {@link Optional} containing the most recent snapshot, or empty if none is available yet
     */
    @NonNull
    Optional<BlockProvenSnapshot> latestSnapshot();
}
