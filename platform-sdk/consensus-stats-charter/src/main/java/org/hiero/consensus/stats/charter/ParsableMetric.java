// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

public record ParsableMetric(
        @NonNull String name,
        Map<String, Integer> seriesLocationPerFile,
        @NonNull String description) {}
