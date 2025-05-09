// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.test.fixtures.PlatformTest;
import com.swirlds.platform.test.fixtures.consensus.ConsensusTestParams;
import com.swirlds.platform.test.fixtures.consensus.ConsensusTestRunner;
import java.util.List;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.config.EventConfig_;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Consensus Tests")
class ConsensusTests extends PlatformTest {

    /**
     * Number of iterations in each test. An iteration is to create one graph, and feed it in twice in different
     * topological orders, and check if they match.
     */
    private final int NUM_ITER = 1;

    private boolean ignoreNoSuperMajorityMarkerFile = false;
    private boolean ignoreNoJudgesMarkerFile = false;
    private boolean ignoreCoinRoundMarkerFile = false;

    @AfterEach
    void checkForMarkerFiles() {
        if (!ignoreNoSuperMajorityMarkerFile) {
            assertMarkerFile(ConsensusImpl.NO_SUPER_MAJORITY_MARKER_FILE, false);
        }
        if (!ignoreNoJudgesMarkerFile) {
            assertMarkerFile(ConsensusImpl.NO_JUDGES_MARKER_FILE, false);
        }
        if (!ignoreCoinRoundMarkerFile) {
            assertMarkerFile(ConsensusImpl.COIN_ROUND_MARKER_FILE, false);
        }
        assertMarkerFile(ConsensusImpl.CONSENSUS_EXCEPTION_MARKER_FILE, false);
    }

    /**
     * Create a list of platform contexts to use for testing.
     * @return a list of platform contexts
     */
    private List<PlatformContext> contexts() {
        return List.of(
                createPlatformContext(
                        null,
                        configBuilder ->
                                configBuilder.withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, false)),
                createPlatformContext(
                        null,
                        configBuilder ->
                                configBuilder.withValue(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true)));
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#orderInvarianceTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Order Invariance Tests")
    void orderInvarianceTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::orderInvarianceTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#reconnectSimulation")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Reconnect Simulation")
    @ParameterizedTest
    void reconnectSimulation(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::reconnect)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#staleEvent")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Stale Events Tests")
    @ParameterizedTest
    void staleEvent(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::stale)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#forkingTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Forking Tests")
    void forkingTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::forkingTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
        // Some forking tests make too many forkers.  When there is  > 1/3 nodes forking, both no super majority and
        // possibly no judges can result. This is expected, so ignore the marker file generated for these tests.
        ignoreNoSuperMajorityMarkerFile = true;
        ignoreNoJudgesMarkerFile = true;
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#partitionTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Partition Tests")
    void partitionTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::partitionTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#subQuorumPartitionTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Sub Quorum Partition Tests")
    void subQuorumPartitionTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::subQuorumPartitionTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#cliqueTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Clique Tests")
    void cliqueTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::cliqueTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#variableRateTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Variable Rate Tests")
    void variableRateTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::variableRateTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#nodeUsesStaleOtherParents")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Node Uses Stale Other Parents")
    void nodeUsesStaleOtherParents(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::usesStaleOtherParents)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#nodeProvidesStaleOtherParents")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Node Provides Stale Other Parents")
    void nodeProvidesStaleOtherParents(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::providesStaleOtherParents)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#quorumOfNodesGoDownTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Quorum Of Nodes Go Down Tests")
    void quorumOfNodesGoDownTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::quorumOfNodesGoDown)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#subQuorumOfNodesGoDownTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Sub Quorum Of Nodes Go Down Tests")
    void subQuorumOfNodesGoDownTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::subQuorumOfNodesGoDown)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#orderInvarianceTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Repeated Timestamp Test")
    void repeatedTimestampTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::repeatedTimestampTest)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#ancientEventTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Consensus Receives Ancient Event")
    void ancientEventTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::ancient)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#restartWithEventsParams")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Node restart with events")
    void fastRestartWithEvents(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::restart)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#nodeRemoveTestParams")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Remove a node from the address book at restart")
    void nodeRemoveTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::removeNode)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#orderInvarianceTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Genesis Snapshot Tests")
    void genesisSnapshotTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::genesisSnapshotTest)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.consensus.ConsensusTestArgs#threeNetworkTypes")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Consensus Freeze Tests")
    void consensusFreezeTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::consensusFreezeTests)
                .setParams(params)
                .setContexts(contexts())
                .setIterations(NUM_ITER)
                .run();
    }
}
