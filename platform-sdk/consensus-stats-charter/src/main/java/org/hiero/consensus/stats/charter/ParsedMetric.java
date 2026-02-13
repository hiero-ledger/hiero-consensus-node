// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;

public record ParsedMetric(@NonNull String name, int headerIndex, @NonNull String description) {}
