// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.snapshot;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;

/**
 * A description of a signed state file and its associated round number.
 *
 * @param stateDirectory the path of the state directory.
 * @param metadata  the metadata of the signed state
 */
public record SavedStateInfo(@NonNull Path stateDirectory, @NonNull SavedStateMetadata metadata) {}
