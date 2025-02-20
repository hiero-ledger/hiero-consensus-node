// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.consensus;

import static com.swirlds.common.test.fixtures.WeightGenerators.RANDOM;
import static com.swirlds.platform.test.consensus.ConsensusTestArgs.RANDOM_WEIGHT_DESC;

import com.swirlds.common.test.fixtures.junit.tags.TestComponentTags;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.eventhandling.EventConfig;
import com.swirlds.platform.eventhandling.EventConfig_;
import com.swirlds.platform.test.PlatformTest;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

    @BeforeAll
    public static void initConfig() {
        new TestConfigBuilder().getOrCreateConfig();
    }

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
     * Replaces the {@link com.swirlds.common.context.PlatformContext} in the test parameters with a new one built from
     * the {@link PlatformTest#createPlatformContext(Function, Function)} and preserves the birthRound configuration
     * setting.
     *
     * @param params the test parameters
     * @return the modified test parameters
     */
    @NonNull
    private ConsensusTestParams modifyParams(@NonNull final ConsensusTestParams params) {
        return new ConsensusTestParams(
                createPlatformContext(
                        null,
                        configBuilder -> configBuilder.withValue(
                                EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD,
                                params.platformContext()
                                        .getConfiguration()
                                        .getConfigData(EventConfig.class)
                                        .useBirthRoundAncientThreshold())),
                params.numNodes(),
                params.weightGenerator(),
                params.weightDesc(),
                params.seeds());
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#orderInvarianceTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Order Invariance Tests")
    void orderInvarianceTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::orderInvarianceTests)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#reconnectSimulation")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Reconnect Simulation")
    @ParameterizedTest
    void reconnectSimulation(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::reconnect)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#staleEvent")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Stale Events Tests")
    @ParameterizedTest
    void staleEvent(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::stale)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#forkingTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Forking Tests")
    void forkingTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::forkingTests)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
        // Some forking tests make too many forkers.  When there is  > 1/3 nodes forking, both no super majority and
        // possibly no judges can result. This is expected, so ignore the marker file generated for these tests.
        ignoreNoSuperMajorityMarkerFile = true;
        ignoreNoJudgesMarkerFile = true;
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#partitionTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Partition Tests")
    void partitionTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::partitionTests)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#subQuorumPartitionTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Sub Quorum Partition Tests")
    void subQuorumPartitionTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::subQuorumPartitionTests)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#cliqueTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Clique Tests")
    void cliqueTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::cliqueTests)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#variableRateTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Variable Rate Tests")
    void variableRateTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::variableRateTests)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#nodeUsesStaleOtherParents")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Node Uses Stale Other Parents")
    void nodeUsesStaleOtherParents(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::usesStaleOtherParents)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#nodeProvidesStaleOtherParents")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Node Provides Stale Other Parents")
    void nodeProvidesStaleOtherParents(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::providesStaleOtherParents)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#quorumOfNodesGoDownTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Quorum Of Nodes Go Down Tests")
    void quorumOfNodesGoDownTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::quorumOfNodesGoDown)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#subQuorumOfNodesGoDownTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Sub Quorum Of Nodes Go Down Tests")
    void subQuorumOfNodesGoDownTests(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::subQuorumOfNodesGoDown)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#orderInvarianceTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Repeated Timestamp Test")
    void repeatedTimestampTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::repeatedTimestampTest)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#ancientEventTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Consensus Receives Ancient Event")
    void ancientEventTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::ancient)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#restartWithEventsParams")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Node restart with events")
    void fastRestartWithEvents(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::restart)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#nodeRemoveTestParams")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Remove a node from the address book at restart")
    void nodeRemoveTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::removeNode)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }

    @Test
    void syntheticSnapshotTest() {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::syntheticSnapshot)
                .setParams(new ConsensusTestParams(createDefaultPlatformContext(), 4, RANDOM, RANDOM_WEIGHT_DESC))
                .setIterations(NUM_ITER)
                .run();
    }

    @ParameterizedTest
    @MethodSource("com.swirlds.platform.test.consensus.ConsensusTestArgs#orderInvarianceTests")
    @Tag(TestComponentTags.PLATFORM)
    @Tag(TestComponentTags.CONSENSUS)
    @DisplayName("Genesis Snapshot Tests")
    void genesisSnapshotTest(final ConsensusTestParams params) {
        ConsensusTestRunner.create()
                .setTest(ConsensusTestDefinitions::genesisSnapshotTest)
                .setParams(modifyParams(params))
                .setIterations(NUM_ITER)
                .run();
    }
}
