// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import java.util.List;
import org.hiero.otter.fixtures.result.MultipleNodeLogResults;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;

/**
 * Implementation of {@link MultipleNodeLogResults} that stores the log results for multiple nodes.
 *
 * @param results the list of log results for individual nodes
 */
public record MultipleNodeLogResultsImpl(List<SingleNodeLogResult> results) implements MultipleNodeLogResults {}
