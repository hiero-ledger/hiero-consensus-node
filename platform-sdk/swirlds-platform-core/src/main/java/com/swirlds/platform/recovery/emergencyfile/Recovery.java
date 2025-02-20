// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.emergencyfile;

import com.fasterxml.jackson.annotation.JsonProperty;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The top level of the emergency recovery YAML structure.
 *
 * @param state
 * 		information about the state written to disk
 * @param bootstrap
 * 		information about the state used to bootstrap event recovery. Not written during normal
 * 		operation. Only written during event recovery.
 * @param pkg information about where to find the emergency recovery package
 * @param stream information about the various file streams
 */
public record Recovery(
        @NonNull State state,
        @Nullable Bootstrap bootstrap,
        @Nullable @JsonProperty("package") Package pkg,
        @Nullable Stream stream) {}
