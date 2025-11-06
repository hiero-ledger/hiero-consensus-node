// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static com.swirlds.common.test.fixtures.WeightGenerators.BALANCED;
import static com.swirlds.common.test.fixtures.WeightGenerators.BALANCED_REAL_WEIGHT;
import static com.swirlds.common.test.fixtures.WeightGenerators.INCREMENTING;
import static com.swirlds.common.test.fixtures.WeightGenerators.ONE_THIRD_ZERO_WEIGHT;
import static com.swirlds.common.test.fixtures.WeightGenerators.RANDOM;
import static com.swirlds.common.test.fixtures.WeightGenerators.RANDOM_REAL_WEIGHT;
import static com.swirlds.common.test.fixtures.WeightGenerators.SINGLE_NODE_STRONG_MINORITY;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.test.fixtures.consensus.ConsensusTestParams;
import java.util.stream.Stream;

public class ConsensusTestArgs {

    public static final String BALANCED_WEIGHT_DESC = "Balanced Weight";
    public static final String BALANCED_REAL_WEIGHT_DESC = "Balanced Weight, Real Total Weight Value";
    public static final String INCREMENTAL_NODE_WEIGHT_DESC = "Incremental Node Weight";
    public static final String SINGLE_NODE_STRONG_MINORITY_DESC = "Single Node With Strong Minority Weight";
    public static final String ONE_THIRD_NODES_ZERO_WEIGHT_DESC = "One Third of Nodes Have Zero Weight";
    public static final String RANDOM_WEIGHT_DESC = "Random Weight, Real Total Weight Value";
    /** The default platform context to use for tests. */
    public static final PlatformContext DEFAULT_PLATFORM_CONTEXT =
            TestPlatformContextBuilder.create().build();

    static Stream<ConsensusTestParams> orderInvarianceTests() {
        return Stream.of(
                new ConsensusTestParams(2, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(2, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, ONE_THIRD_ZERO_WEIGHT, ONE_THIRD_NODES_ZERO_WEIGHT_DESC),
                new ConsensusTestParams(50, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC),
                new ConsensusTestParams(50, RANDOM, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> reconnectSimulation() {
        return Stream.of(
                new ConsensusTestParams(4, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(4, ONE_THIRD_ZERO_WEIGHT, ONE_THIRD_NODES_ZERO_WEIGHT_DESC),
                new ConsensusTestParams(4, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC),
                new ConsensusTestParams(10, SINGLE_NODE_STRONG_MINORITY, SINGLE_NODE_STRONG_MINORITY_DESC),
                new ConsensusTestParams(10, ONE_THIRD_ZERO_WEIGHT, ONE_THIRD_NODES_ZERO_WEIGHT_DESC),
                new ConsensusTestParams(10, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> staleEvent() {
        return Stream.of(
                new ConsensusTestParams(6, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(6, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(6, ONE_THIRD_ZERO_WEIGHT, ONE_THIRD_NODES_ZERO_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> branchingTests() {
        return Stream.of(
                new ConsensusTestParams(2, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> partitionTests() {
        return Stream.of(
                // Uses balanced weights for 4 so that each partition can continue to create events.
                // This limitation if one of the test, not the consensus algorithm.
                // new ConsensusTestParams(4, BALANCED_REAL_WEIGHT,
                // BALANCED_REAL_WEIGHT_DESC),

                // Use uneven stake such that no single node has a strong minority and could be
                // put in a partition by itself and no longer generate events. This limitation if
                // one
                // of the test, not the consensus algorithm.
                // new ConsensusTestParams(5, INCREMENTING,
                // INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> subQuorumPartitionTests() {
        return Stream.of(
                new ConsensusTestParams(7, BALANCED_REAL_WEIGHT, BALANCED_REAL_WEIGHT_DESC),
                new ConsensusTestParams(9, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(
                        9,
                        ONE_THIRD_ZERO_WEIGHT,
                        ONE_THIRD_NODES_ZERO_WEIGHT_DESC,
                        // used to cause a stale mismatch, documented in
                        // swirlds/swirlds-platform/issues/5007
                        3101029514312517274L,
                        -4115810541946354865L));
    }

    static Stream<ConsensusTestParams> cliqueTests() {
        return Stream.of(
                new ConsensusTestParams(4, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(9, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> variableRateTests() {
        return Stream.of(
                new ConsensusTestParams(2, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> nodeUsesStaleOtherParents() {
        return Stream.of(
                new ConsensusTestParams(2, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(
                        4,
                        INCREMENTING,
                        INCREMENTAL_NODE_WEIGHT_DESC,
                        // seed was failing because Consensus ratio is 0.6611, which is less
                        // than what was previously
                        // set
                        458078453642476240L),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> nodeProvidesStaleOtherParents() {
        return Stream.of(
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> quorumOfNodesGoDownTests() {
        return Stream.of(
                new ConsensusTestParams(2, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> subQuorumOfNodesGoDownTests() {
        return Stream.of(
                new ConsensusTestParams(2, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(4, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, RANDOM_REAL_WEIGHT, RANDOM_WEIGHT_DESC));
    }

    static Stream<ConsensusTestParams> ancientEventTests() {
        return Stream.of(new ConsensusTestParams(4, BALANCED, BALANCED_WEIGHT_DESC));
    }

    public static Stream<ConsensusTestParams> restartWithEventsParams() {
        return Stream.of(
                new ConsensusTestParams(5, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(10, RANDOM, RANDOM_WEIGHT_DESC),
                new ConsensusTestParams(20, RANDOM, RANDOM_WEIGHT_DESC));
    }

    public static Stream<ConsensusTestParams> nodeRemoveTestParams() {
        return Stream.of(new ConsensusTestParams(4, RANDOM, RANDOM_WEIGHT_DESC));
    }

    public static Stream<ConsensusTestParams> threeNetworkTypes() {
        return Stream.of(
                new ConsensusTestParams(2, INCREMENTING, INCREMENTAL_NODE_WEIGHT_DESC),
                new ConsensusTestParams(9, BALANCED, BALANCED_WEIGHT_DESC),
                new ConsensusTestParams(50, RANDOM, RANDOM_WEIGHT_DESC));
    }
}
