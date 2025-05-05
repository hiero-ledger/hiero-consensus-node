// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

/**
 * Default implementation of {@link org.hiero.otter.fixtures.assertions.MultipleNodeConsensusResultsAssert}
 */
public record MultipleNodeConsensusResultsImpl(@NonNull List<SingleNodeConsensusResult> results)
        implements MultipleNodeConsensusResults {}
