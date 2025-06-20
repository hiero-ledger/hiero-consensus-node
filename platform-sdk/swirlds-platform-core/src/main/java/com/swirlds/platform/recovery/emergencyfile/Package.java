// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.emergencyfile;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * Information about where to find the emergency recovery package
 * @param locations the locations of the package
 */
public record Package(@NonNull List<Location> locations) {}
