// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.AbstractAssert;
import org.assertj.core.data.Percentage;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.otter.fixtures.OtterAssertions;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodeReconnectResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;

import static java.util.Comparator.comparingInt;

/**
 * Assertions for {@link MultipleNodeReconnectResults}.
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class MultipleNodeReconnectResultsAssert
        extends AbstractAssert<MultipleNodeReconnectResultsAssert, MultipleNodeReconnectResults> {

    /**
     * Creates a new instance of {@link MultipleNodeReconnectResultsAssert}
     *
     * @param actual the actual {@link MultipleNodeConsensusResults} to assert
     */
    public MultipleNodeReconnectResultsAssert(@Nullable final MultipleNodeReconnectResults actual) {
        super(actual, MultipleNodeReconnectResultsAssert.class);
    }

    /**
     * Creates an assertion for the given {@link MultipleNodeReconnectResults}.
     *
     * @param actual the {@link MultipleNodeReconnectResults} to assert
     * @return an assertion for the given {@link MultipleNodeReconnectResults}
     */
    @NonNull
    public static MultipleNodeReconnectResultsAssert assertThat(@Nullable final MultipleNodeReconnectResults actual) {
        return new MultipleNodeReconnectResultsAssert(actual);
    }

    /**
     * Verifies that no nodes have reconnects.
     *
     * @return this assertion object for method chaining
     */
    public MultipleNodeReconnectResultsAssert haveNoReconnects() {
        isNotNull();

        for (final SingleNodeReconnectResult result : actual.reconnectResults()) {
            OtterAssertions.assertThat(result).hasNoReconnects();
        }

        return this;
    }
}
