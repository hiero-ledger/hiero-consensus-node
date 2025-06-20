// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.emergencyfile;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Information about the various file streams
 * @param intervals the output intervals of the streams
 */
public record Stream(@NonNull Intervals intervals) {}
