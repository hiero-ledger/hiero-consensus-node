// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * A description of a signed state file and its associated round number.
 *
 * @param stateFile the path of the SignedState.swh file.
 * @param metadata  the metadata of the signed state
 */
public record SavedStateInfo(@NonNull Path stateFile, @NonNull SavedStateMetadata metadata) {

    /**
     * Get the parent directory.
     *
     * @return the parent directory
     */
    @NonNull
    public Path getDirectory() {
        return stateFile.toAbsolutePath().getParent();
    }
}
