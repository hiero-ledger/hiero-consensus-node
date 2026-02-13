// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.stats.charter;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A named series of metric values extracted from a single node's stats file.
 *
 * @param nodeName display name
 * @param values   metric values per time-step ({@code null} entries for missing data)
 */
public record ParsedSeries(
        @NonNull String nodeName, @NonNull List<Double> values) {}
