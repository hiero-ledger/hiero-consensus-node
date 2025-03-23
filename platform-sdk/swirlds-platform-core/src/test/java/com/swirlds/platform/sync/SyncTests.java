// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.sync;

import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.common.utility.CompareTo.max;
import static com.swirlds.platform.test.fixtures.event.EventUtils.integerPowerDistribution;
import static org.hiero.consensus.model.event.AncientMode.BIRTH_ROUND_THRESHOLD;
import static org.hiero.consensus.model.event.AncientMode.GENERATION_THRESHOLD;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.test.fixtures.threading.ReplaceSyncPhaseParallelExecutor;
import com.swirlds.common.test.fixtures.threading.SyncPhaseParallelExecutor;
import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.shadowgraph.ShadowEvent;
import com.swirlds.platform.test.fixtures.event.emitter.EventEmitterFactory;
import com.swirlds.platform.test.fixtures.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.fixtures.event.generator.GraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSourceFactory;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory;
import com.swirlds.platform.test.fixtures.graph.PartitionedGraphCreator;
import com.swirlds.platform.test.fixtures.graph.SplitForkGraphCreator;
import com.swirlds.platform.test.fixtures.sync.SyncNode;
import com.swirlds.platform.test.fixtures.sync.SyncTestExecutor;
import com.swirlds.platform.test.fixtures.sync.SyncTestParams;
import com.swirlds.platform.test.fixtures.sync.SyncTestUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileNotFoundException;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Sync Integration/Unit Tests")
public class SyncTests {

    private static final boolean platformLoggingEnabled = true;

    private static Stream<Arguments> bothAncientModes() {
        return Stream.of(Arguments.of(GENERATION_THRESHOLD), Arguments.of(BIRTH_ROUND_THRESHOLD));
    }

    private static Stream<Arguments> fourNodeGraphParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(4, 100, 20, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 0, 20, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 20, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 0, 20, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 20, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> tenNodeGraphParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 100, 50, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 0, 50, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 50, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 0, 50, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 50, 50, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> tenNodeBigGraphParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 1000, 2000, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 0, 2000, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 2000, 2000, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 2000, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 0, 2000, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 2000, 2000, BIRTH_ROUND_THRESHOLD)));
    }

    /**
     * Supplies test values for {@link #simpleGraph(SyncTestParams)}
     */
    private static Stream<Arguments> simpleFourNodeGraphParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(4, 10, 0, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 1, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 0, 1, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 1, 1, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 8, 1, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 0, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 1, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 0, 1, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 1, 1, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 8, 1, BIRTH_ROUND_THRESHOLD)));
    }

    /**
     * Supplies edge case test values for {@link #simpleGraph(SyncTestParams)}
     */
    private static Stream<Arguments> edgeCaseGraphParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 0, 20, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 20, 0, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 20, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 20, 0, 0, BIRTH_ROUND_THRESHOLD)));
    }

    /**
     * Supplies test values for {@link #partitionedGraph(SyncTestParams)}
     */
    private static Stream<Arguments> partitionedGraphParams() {
        return Stream.of(
                // Partitioned graphs with common events
                Arguments.of(new SyncTestParams(10, 30, 5, 5, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 30, 5, 5, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 200, 100, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 200, 0, 100, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 200, 100, 100, GENERATION_THRESHOLD)),
                // Partitioned graphs with no common events
                Arguments.of(new SyncTestParams(20, 0, 100, 0, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 0, 0, 100, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 0, 100, 100, GENERATION_THRESHOLD)),
                // Partitioned graphs with common events
                Arguments.of(new SyncTestParams(10, 30, 5, 5, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 30, 5, 5, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 200, 100, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 200, 0, 100, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 200, 100, 100, BIRTH_ROUND_THRESHOLD)),
                // Partitioned graphs with no common events
                Arguments.of(new SyncTestParams(20, 0, 100, 0, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 0, 0, 100, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(20, 0, 100, 100, BIRTH_ROUND_THRESHOLD)));
    }

    /**
     * Supplies test values for {@link #testCallerExceptionDuringSyncPhase(SyncTestParams, int, int)} and
     * {@link #testListenerExceptionDuringSyncPhase(SyncTestParams, int, int)}
     */
    private static Stream<Arguments> exceptionParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD), 1, 1),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD), 1, 2),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD), 2, 1),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD), 2, 2),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD), 3, 1),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD), 3, 2),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD), 1, 1),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD), 1, 2),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD), 2, 1),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD), 2, 2),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD), 3, 1),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD), 3, 2));
    }

    private static Stream<Arguments> splitForkParams() {
        return Stream.of(
                // This seed makes the caller send the whole graph, should not be the case once we change the tip
                // definition
                Arguments.of(new SyncTestParams(4, 100, 20, 1, 4956163591276672768L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 1, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 1, 20, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 20, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 100, 50, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 100, GENERATION_THRESHOLD)),
                // This seed makes the caller send the whole graph, should not be the case once we change the tip
                // definition
                Arguments.of(new SyncTestParams(4, 100, 20, 1, 4956163591276672768L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 1, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 1, 20, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 20, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 100, 50, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 100, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> splitForkParamsBreakingSeed() {
        return Stream.of(
                // This seed used to make the caller send the whole graph back when the definition of a tip was an
                // event with no children (self or other). Now that the definition of a tip is an event with no
                // self-child, this seed passes.
                Arguments.of(new SyncTestParams(4, 100, 20, 1, 4956163591276672768L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 1, 4956163591276672768L, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> largeGraphParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 1000, 500, 200, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 200, 500, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 500, 500, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 500, 200, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 200, 500, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 500, 500, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> noCommonEventsParams() {
        return Stream.of(
                Arguments.of(new SyncTestParams(4, 0, 50, 20, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 0, 20, 50, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 0, 50, 50, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 500, 200, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 200, 500, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 500, 500, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 0, 50, 20, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 0, 20, 50, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 0, 50, 50, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 500, 200, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 200, 500, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 500, 500, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> tipsChangeBreakingSeed() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 0, 20, 0, 6238590233436833292L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 8, 1, 8824331216639179768L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, -909134053413382981L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, 5236225801504915258L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 1, 1, -3204404663467002969L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, -4776092416980912346L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 20, 0, 6238590233436833292L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 8, 1, 8824331216639179768L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, -909134053413382981L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, 5236225801504915258L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 10, 1, 1, -3204404663467002969L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 0, 0, 20, -4776092416980912346L, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> simpleGraphBreakingSeed() {
        return Stream.of(
                Arguments.of(new SyncTestParams(4, 100, 20, 20, -5979073137457357235L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, 1861589538493329478L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(4, 100, 20, 20, -5979073137457357235L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 50, 50, 1861589538493329478L, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> tipExpiresBreakingSeed() {
        return Stream.of(
                Arguments.of(new SyncTestParams(10, 100, 0, 50, 1152284535185134815L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 100, 0, 50, -8664085824668001150L, GENERATION_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 0, 50, 1152284535185134815L, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(new SyncTestParams(10, 1000, 0, 50, -8664085824668001150L, BIRTH_ROUND_THRESHOLD)));
    }

    private static Stream<Arguments> requiredEventsExpire() {
        return Stream.of(
                Arguments.of(1, new SyncTestParams(10, 100, 0, 1000, GENERATION_THRESHOLD)),
                Arguments.of(1, new SyncTestParams(10, 200, 100, 1000, GENERATION_THRESHOLD)),
                Arguments.of(1, new SyncTestParams(10, 200, 200, 1000, GENERATION_THRESHOLD)),
                Arguments.of(2, new SyncTestParams(10, 100, 0, 1000, GENERATION_THRESHOLD)),
                Arguments.of(2, new SyncTestParams(10, 200, 100, 1000, GENERATION_THRESHOLD)),
                Arguments.of(2, new SyncTestParams(10, 200, 200, 1000, GENERATION_THRESHOLD)),
                Arguments.of(1, new SyncTestParams(10, 100, 0, 1000, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(1, new SyncTestParams(10, 200, 100, 1000, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(1, new SyncTestParams(10, 200, 200, 1000, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(2, new SyncTestParams(10, 100, 0, 1000, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(2, new SyncTestParams(10, 200, 100, 1000, BIRTH_ROUND_THRESHOLD)),
                Arguments.of(2, new SyncTestParams(10, 200, 200, 1000, BIRTH_ROUND_THRESHOLD)));
    }

    @BeforeAll
    public static void setup() throws FileNotFoundException, ConstructableRegistryException {
        new TestConfigBuilder().getOrCreateConfig();

        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");

        if (platformLoggingEnabled) {
            loadLog4jContext();
        }
    }

    /**
     * Tests small, simple graphs using a standard generator and no forking sources.
     */
    @ParameterizedTest
    @MethodSource({
        "simpleGraphBreakingSeed",
        "simpleFourNodeGraphParams",
        "fourNodeGraphParams",
        "tenNodeGraphParams",
        "edgeCaseGraphParams"
    })
    void simpleGraph(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        executor.setGraphCustomization((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests skipping sync initialization bytes
     */
    @ParameterizedTest
    @MethodSource({"simpleFourNodeGraphParams"})
    void skipInit(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        executor.setGraphCustomization((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests syncing graphs with forking event sources.
     */
    @ParameterizedTest
    @MethodSource({"fourNodeGraphParams", "tenNodeGraphParams"})
    void forkingGraph(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        executor.setCallerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(), 0, factory.newForkingShuffledGenerator(), params.getAncientMode()));
        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newForkingShuffledGenerator(),
                params.getAncientMode()));

        executor.execute();

        // Some extra events could be transferred in the case of a split fork graph. This is explicitly tested in
        // splitForkGraph()
        SyncValidator.assertRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests cases where each node has one branch of a fork. Neither node knows there is a fork until after the sync.
     */
    @ParameterizedTest
    @MethodSource({"splitForkParams", "splitForkParamsBreakingSeed"})
    void splitForkGraph(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        final int creatorToFork = 0;
        final int callerOtherParent = 1;
        final int listenerOtherParent = 2;

        executor.setCallerSupplier((factory) ->
                new SyncNode(params.getNumNetworkNodes(), 0, factory.newStandardEmitter(), params.getAncientMode()));
        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newStandardEmitter(),
                params.getAncientMode()));

        executor.setInitialGraphCreation((caller, listener) -> {
            caller.generateAndAdd(params.getNumCommonEvents());
            listener.generateAndAdd(params.getNumCommonEvents());

            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        executor.setCustomInitialization((caller, listener) -> {
            SplitForkGraphCreator.createSplitForkConditions(
                    (StandardEventEmitter) caller.getEmitter(),
                    creatorToFork,
                    callerOtherParent,
                    params.getNumCommonEvents(),
                    params.getNumNetworkNodes());
            SplitForkGraphCreator.createSplitForkConditions(
                    (StandardEventEmitter) listener.getEmitter(),
                    creatorToFork,
                    listenerOtherParent,
                    params.getNumCommonEvents(),
                    params.getNumNetworkNodes());
        });

        executor.execute();

        // In split fork graphs, some extra events will be sent because each node has a different tip for the same
        // creator, causing each to think the other does not have any ancestors of that creator's event when they in
        // fact do.
        SyncValidator.assertRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests syncing partitioned graphs where the caller and listener are in different partitions.
     */
    @ParameterizedTest
    @MethodSource({"partitionedGraphParams"})
    void partitionedGraph(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        final int numPartitionedNodes = (int) Math.ceil(((double) params.getNumNetworkNodes()) / 3.0);

        final List<Integer> callerPartitionNodes =
                IntStream.range(0, numPartitionedNodes).boxed().collect(Collectors.toList());
        final List<Integer> listenerPartitionNodes = IntStream.range(numPartitionedNodes, params.getNumNetworkNodes())
                .boxed()
                .collect(Collectors.toList());

        executor.setCallerSupplier((factory) ->
                new SyncNode(params.getNumNetworkNodes(), 0, factory.newStandardEmitter(), params.getAncientMode()));
        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newStandardEmitter(),
                params.getAncientMode()));

        executor.setInitialGraphCreation((caller, listener) -> {
            caller.generateAndAdd(params.getNumCommonEvents());
            listener.generateAndAdd(params.getNumCommonEvents());

            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        executor.setCustomInitialization((caller, listener) -> {
            PartitionedGraphCreator.setupPartitionForNode(
                    caller, callerPartitionNodes, params.getNumCommonEvents(), params.getNumNetworkNodes());
            PartitionedGraphCreator.setupPartitionForNode(
                    listener, listenerPartitionNodes, params.getNumCommonEvents(), params.getNumNetworkNodes());
        });

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * This test verifies that no events are transferred when no booleans are received from the other node.
     */
    @ParameterizedTest
    @MethodSource("tenNodeGraphParams")
    void testNoBooleansReceived(final SyncTestParams params) {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        final ParallelExecutor callerExecutor =
                new ReplaceSyncPhaseParallelExecutor(getStaticThreadManager(), 2, 1, () -> null);
        final ParallelExecutor listenerExecutor = new CachedPoolParallelExecutor(getStaticThreadManager(), "sync-node");
        listenerExecutor.start();

        // Setup parallel executors
        executor.setCallerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(), 0, factory.newShuffledEmitter(), callerExecutor, params.getAncientMode()));
        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newShuffledEmitter(),
                listenerExecutor,
                params.getAncientMode()));

        assertThrows(ParallelExecutionException.class, executor::execute, "Unexpected exception during sync.");

        SyncValidator.assertExceptionThrown(executor.getCaller(), executor.getListener());
        SyncValidator.assertNoEventsTransferred(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests that when an exception is thrown by the caller in each phase and task, an exception is propagated up for
     * both nodes and that no events are transferred.
     */
    @ParameterizedTest
    @MethodSource("exceptionParams")
    void testCallerExceptionDuringSyncPhase(final SyncTestParams params, final int phaseToThrowIn, final int taskNum)
            throws Exception {

        final SyncTestExecutor executor = new SyncTestExecutor(params);

        final ParallelExecutor callerExecutor =
                new ReplaceSyncPhaseParallelExecutor(getStaticThreadManager(), phaseToThrowIn, taskNum, () -> {
                    executor.getCaller().getConnection().disconnect();
                    throw new SocketException();
                });

        executor.setCallerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(), 0, factory.newShuffledEmitter(), callerExecutor, params.getAncientMode()));

        runExceptionDuringSyncPhase(phaseToThrowIn, taskNum, executor);
    }

    /**
     * Tests that when an exception is thrown by the listener in each phase and task, an exception is propagated up for
     * both nodes and that no events are transferred.
     */
    @ParameterizedTest
    @MethodSource("exceptionParams")
    void testListenerExceptionDuringSyncPhase(final SyncTestParams params, final int phaseToThrowIn, final int taskNum)
            throws Exception {

        final SyncTestExecutor executor = new SyncTestExecutor(params);

        final ParallelExecutor listenerExecutor =
                new ReplaceSyncPhaseParallelExecutor(getStaticThreadManager(), phaseToThrowIn, taskNum, () -> {
                    executor.getListener().getConnection().disconnect();
                    throw new SocketException();
                });

        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                0,
                factory.newShuffledEmitter(),
                listenerExecutor,
                params.getAncientMode()));

        runExceptionDuringSyncPhase(phaseToThrowIn, taskNum, executor);
    }

    private void runExceptionDuringSyncPhase(
            final int phaseToThrowIn, final int taskNum, final SyncTestExecutor executor) throws Exception {

        try {
            executor.execute();
        } catch (final ParallelExecutionException e) {
            // expected, ignore
        }

        final SyncNode caller = executor.getCaller();
        final SyncNode listener = executor.getListener();

        if (!(phaseToThrowIn == 3 && taskNum == 1)) {
            SyncValidator.assertNoEventsTransferred(caller, listener);
        }
    }

    /**
     * Test a sync where, after the common events, the caller has many events one a node, and the listener has no events
     * for that node.
     */
    @ParameterizedTest
    @MethodSource({"fourNodeGraphParams", "tenNodeGraphParams", "noCommonEventsParams"})
    void testUnknownCreator(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        // The node id that is unknown to the caller after the common events are generated
        final int unknownCallerCreator = 0;

        // Fully connected, evenly balanced other parent matrix
        final List<List<Double>> normalOtherMatrix =
                OtherParentMatrixFactory.createBalancedOtherParentMatrix(params.getNumNetworkNodes());

        // Matrix that does not allow the unknown creator to be selected as an other parent
        final List<List<Double>> unknownCreatorOtherMatrix =
                OtherParentMatrixFactory.createShunnedNodeOtherParentAffinityMatrix(
                        params.getNumNetworkNodes(), unknownCallerCreator);

        executor.setCustomInitialization((caller, listener) -> {
            for (final SyncNode node : List.of(caller, listener)) {

                final GraphGenerator generator = node.getEmitter().getGraphGenerator();
                generator.setOtherParentAffinity(((random, eventIndex, previousValue) -> {
                    if (eventIndex < params.getNumCommonEvents()) {
                        // Use the normal matrix for common events
                        return normalOtherMatrix;
                    } else {
                        // after common events, do not use the unknown creator as an other parent to prevent
                        // forking between the caller and listener
                        return unknownCreatorOtherMatrix;
                    }
                }));
            }
        });

        // Do not add events created by the unknown creator to the caller graph to fulfill the premise of this test
        executor.setCallerAddToGraphTest(
                (indexedEvent -> indexedEvent.getCreatorId().id() != unknownCallerCreator));

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
    }

    /**
     * Tests fallen behind detection works
     */
    @ParameterizedTest
    @MethodSource("bothAncientModes")
    void fallenBehind(@NonNull final AncientMode ancientMode) throws Exception {
        final SyncTestParams params = new SyncTestParams(4, 100, 20, 20, ancientMode);

        final long callerExpiredThreshold = 100;
        final long callerMaximumIndicator = 200;
        final long listenerExpiredThreshold = 300;
        final long listenerMaximumIndicator = 400;

        runFallenBehindTest(
                params,
                callerExpiredThreshold,
                callerMaximumIndicator,
                listenerExpiredThreshold,
                listenerMaximumIndicator);
    }

    /**
     * Tests fallen behind detection works with one node at genesis
     */
    @ParameterizedTest
    @MethodSource("bothAncientModes")
    void fallenBehindAtGenesis(@NonNull final AncientMode ancientMode) throws Exception {
        final SyncTestParams params = new SyncTestParams(4, 0, 1, 100, ancientMode);

        final long callerExpiredThreshold = ancientMode.getGenesisIndicator();
        final long callerMaximumIndicator = ancientMode.getGenesisIndicator();
        final long listenerAncientThreshold = 200;
        final long listenerMaximumIndicator = 300;

        runFallenBehindTest(
                params,
                callerExpiredThreshold,
                callerMaximumIndicator,
                listenerAncientThreshold,
                listenerMaximumIndicator);
    }

    private void runFallenBehindTest(
            final SyncTestParams params,
            final long callerExpiredThreshold,
            final long callerMaximumIndicator,
            final long listenerExpiredThreshold,
            final long listenerMaximumIndicator)
            throws Exception {
        assertTrue(
                callerExpiredThreshold <= callerMaximumIndicator,
                "Caller event window provided does not represent a fallen behind node.");
        assertTrue(
                listenerExpiredThreshold <= listenerMaximumIndicator,
                "Listener event window provided does not represent a fallen behind node.");
        assertTrue(
                callerMaximumIndicator < listenerExpiredThreshold || listenerMaximumIndicator < callerExpiredThreshold,
                "Event window provided does not represent a fallen behind node.");

        final boolean callerFallenBehind = callerMaximumIndicator < listenerExpiredThreshold;

        final SyncTestExecutor executor = new SyncTestExecutor(params);

        executor.setCallerSupplier((factory) ->
                new SyncNode(params.getNumNetworkNodes(), 0, factory.newStandardEmitter(), params.getAncientMode()));
        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newStandardEmitter(),
                params.getAncientMode()));

        executor.setInitialGraphCreation((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        executor.setCustomPreSyncConfiguration((c, l) -> {
            c.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            0 /* ignored by shadowgraph */,
                            callerMaximumIndicator,
                            callerExpiredThreshold,
                            GENERATION_THRESHOLD));
            l.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            0 /* ignored by shadowgraph */,
                            listenerMaximumIndicator,
                            listenerExpiredThreshold,
                            GENERATION_THRESHOLD));
        });

        executor.execute();

        SyncValidator.assertFallenBehindDetection(callerFallenBehind, executor.getCaller());
        SyncValidator.assertFallenBehindDetection(!callerFallenBehind, executor.getListener());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());

        SyncValidator.assertNoEventsReceived("listener", executor.getListener());
        SyncValidator.assertNoEventsReceived("caller", executor.getCaller());
    }

    @ParameterizedTest
    @MethodSource("bothAncientModes")
    void testBarelyNotFallenBehind(@NonNull final AncientMode ancientMode) throws Exception {
        final SyncTestParams params = new SyncTestParams(4, 2000, 2000, 0, ancientMode);
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        executor.setEventWindowDefinitions((caller, listener) -> {
            long listenerMaxIndicator =
                    SyncTestUtils.getMaxIndicator(listener.getShadowGraph().getTips(), ancientMode);
            // make the min non-ancient indicator slightly below the max indicator
            long listenerNonAncientThreshold = listenerMaxIndicator - (listenerMaxIndicator / 10);
            long listenerMinIndicator = SyncTestUtils.getMinIndicator(
                    listener.getShadowGraph()
                            .findAncestors(listener.getShadowGraph().getTips(), (e) -> true),
                    ancientMode);

            // Expire everything below the listener's min non-ancient indicator on the caller
            caller.expireBelow(listenerNonAncientThreshold);

            long callerMaxIndicator =
                    SyncTestUtils.getMaxIndicator(caller.getShadowGraph().getTips(), ancientMode);
            // make the min non-ancient indicator slightly below the max indicator
            long callerNonAncientThreshold = callerMaxIndicator - (callerMaxIndicator / 10);
            // Setting the caller min indicator to listener ancient threshold - 1 ensures that the caller is not behind
            long callerMinIndicator = listenerNonAncientThreshold - 1;

            listener.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            ROUND_FIRST /* ignored */,
                            Math.max(ancientMode.getGenesisIndicator(), listenerNonAncientThreshold),
                            Math.max(ancientMode.getGenesisIndicator(), listenerMinIndicator),
                            ancientMode));

            caller.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            ROUND_FIRST /* ignored */,
                            Math.max(ancientMode.getGenesisIndicator(), callerNonAncientThreshold),
                            Math.max(ancientMode.getGenesisIndicator(), callerMinIndicator),
                            ancientMode));
        });

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(executor.getCaller(), executor.getListener(), ancientMode);
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Verifies that even if events are expired right before sending, they are still sent.
     */
    @ParameterizedTest
    @MethodSource("bothAncientModes")
    void testSendExpiredEvents(@NonNull final AncientMode ancientMode) throws Exception {
        final SyncTestParams params = new SyncTestParams(4, 20, 10, 0, ancientMode);
        final SyncTestExecutor executor = new SyncTestExecutor(params);
        final AtomicLong indicatorToExpire = new AtomicLong(ancientMode.getGenesisIndicator() - 1);

        // before phase 3, expire all events so that expired events are sent
        executor.setCallerExecutorSupplier(() -> new SyncPhaseParallelExecutor(
                getStaticThreadManager(),
                null,
                () -> {
                    // Expire the events from the hash graph
                    List<ShadowEvent> callerTips =
                            executor.getCaller().getShadowGraph().getTips();

                    // Expire the events from the shadow graph
                    final EventWindow eventWindow = new EventWindow(
                            0 /* ignored by shadowgraph */,
                            ancientMode.getGenesisIndicator(),
                            indicatorToExpire.get() + 1,
                            ancientMode);
                    executor.getCaller().getShadowGraph().updateEventWindow(eventWindow);
                },
                false));

        // we save the max indicator of node 0, so we know what we need to expire to remove a tip
        executor.setCustomPreSyncConfiguration((caller, listener) -> indicatorToExpire.set(
                SyncTestUtils.getMaxIndicator(caller.getShadowGraph().getTips(), ancientMode)));

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(executor.getCaller(), executor.getListener(), ancientMode);
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests a situation where a tip is requested to be expired after it is used in phase 1, issue #3856
     */
    @ParameterizedTest
    @MethodSource({"tenNodeGraphParams", "tenNodeBigGraphParams", "tipExpiresBreakingSeed"})
    void tipExpiresAfterPhase1(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);
        final AtomicLong maximumIndicator = new AtomicLong(
                params.getAncientMode() == BIRTH_ROUND_THRESHOLD
                        ? ConsensusConstants.ROUND_NEGATIVE_INFINITY
                        : EventConstants.GENERATION_UNDEFINED);

        final int creatorIndexToExpire = 0;
        final NodeId creatorIdToExpire = executor.getAddressBook().getNodeId(creatorIndexToExpire);

        // node 0 should not create any events after CommonEvents
        executor.setFactoryConfig((factory) -> factory.getSourceFactory()
                .addCustomSource((index) -> index == creatorIndexToExpire, () -> {
                    final StandardEventSource source0 = new StandardEventSource(false);
                    source0.setNewEventWeight((r, index, prev) -> {
                        if (index <= params.getNumCommonEvents() / 2) {
                            return 1.0;
                        } else {
                            return 0.0;
                        }
                    });
                    return source0;
                }));

        // we save the max indicator of node 0, so we know what we need to expire to remove a tip
        executor.setGraphCustomization((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);

            maximumIndicator.set(
                    params.getAncientMode() == BIRTH_ROUND_THRESHOLD
                            ? ConsensusConstants.ROUND_FIRST
                                    + caller.getEmitter().getGraphGenerator().getMaxBirthRound(creatorIdToExpire)
                            : EventConstants.FIRST_GENERATION
                                    + caller.getEmitter().getGraphGenerator().getMaxGeneration(creatorIdToExpire));
        });

        // before the sync, expire the tip on the listener
        executor.setCustomPreSyncConfiguration((c, l) -> {
            l.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            0 /* ignored by shadowgraph */,
                            maximumIndicator.get() + 2,
                            maximumIndicator.get() + 1,
                            params.getAncientMode()));

            c.updateEventWindow(new EventWindow(
                    0 /* ignored by shadowgraph */,
                    maximumIndicator.get() + 2,
                    max(params.getAncientMode().getGenesisIndicator(), maximumIndicator.get() - 1),
                    params.getAncientMode()));
        });

        // after phase 1, expire the tip on the caller
        final SyncPhaseParallelExecutor parallelExecutor = new SyncPhaseParallelExecutor(
                getStaticThreadManager(),
                () -> executor.getCaller()
                        .updateEventWindow(new EventWindow(
                                0 /* ignored by shadowgraph */,
                                maximumIndicator.get() + 2,
                                maximumIndicator.get() + 1,
                                params.getAncientMode())),
                null,
                true);
        executor.setExecutorSupplier(() -> parallelExecutor);

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests a situation where tips change during a sync, after phase 1
     */
    @ParameterizedTest
    @MethodSource({
        "tipsChangeBreakingSeed",
        "simpleFourNodeGraphParams",
        "fourNodeGraphParams",
        "tenNodeGraphParams",
        "edgeCaseGraphParams"
    })
    void tipsChangeAfterPhase1(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);
        final int numToAddInPhase = 10;

        executor.setGraphCustomization((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        Runnable addEvents = () -> {
            for (SyncNode node : List.of(executor.getCaller(), executor.getListener())) {
                node.generateAndAdd(numToAddInPhase);
            }
        };
        final SyncPhaseParallelExecutor parallelExecutor =
                new SyncPhaseParallelExecutor(getStaticThreadManager(), addEvents, null, true);
        executor.setExecutorSupplier(() -> parallelExecutor);

        executor.execute();

        // since we get a new set of tips before phase 3, it is possible to transfer some duplicate events that were
        // added after the initial tips were exchanged
        SyncValidator.assertRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests a situation where tips change during a sync, after phase 2
     */
    @ParameterizedTest
    @MethodSource({
        "tipsChangeBreakingSeed",
        "simpleFourNodeGraphParams",
        "fourNodeGraphParams",
        "tenNodeGraphParams",
        "edgeCaseGraphParams"
    })
    void tipsChangeAfterPhase2(final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);
        final int numToAddInPhase = 10;

        executor.setGraphCustomization((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });

        Runnable addEvents = () -> {
            for (SyncNode node : List.of(executor.getCaller(), executor.getListener())) {
                node.generateAndAdd(numToAddInPhase);
            }
        };
        final SyncPhaseParallelExecutor parallelExecutor =
                new SyncPhaseParallelExecutor(getStaticThreadManager(), null, addEvents, true);
        executor.setExecutorSupplier(() -> parallelExecutor);

        executor.execute();

        // since we get a new set of tips before phase 3, it is possible to transfer some duplicate events that were
        // added after the initial tips were exchanged
        SyncValidator.assertRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests scenarios in which events that need to be sent to the peer are requested to be expired before they are
     * sent. Because ancient indicators are reserved in a sync, the events should not be expired while a sync is in
     * progress.
     *
     * @param expireAfterPhase the phase after which events that need to be sent should be requested to be expired
     * @param params           Sync parameters
     */
    @ParameterizedTest
    @MethodSource("requiredEventsExpire")
    void requiredEventsExpire(final int expireAfterPhase, final SyncTestParams params) throws Exception {
        final SyncTestExecutor executor = new SyncTestExecutor(params);
        final AtomicLong indicatorToExpire = new AtomicLong(0);

        final NodeId creatorId = executor.getAddressBook().getNodeId(0);

        // Set the indicator to expire such that half the listener's graph, and therefore some events that need
        // to be sent to the caller, will be expired. Since we are hacking birth rounds to be the same as generations,
        // we can use the same indicator for both.
        executor.setCustomPreSyncConfiguration((c, l) ->
                indicatorToExpire.set(l.getEmitter().getGraphGenerator().getMaxGeneration(creatorId) / 2));

        final EventWindow eventWindow = new EventWindow(
                0 /* ignored by shadowgraph */,
                params.getAncientMode().getGenesisIndicator(),
                Math.max(params.getAncientMode().getGenesisIndicator(), indicatorToExpire.get()),
                params.getAncientMode());

        // Expire events from the listener's graph after the supplied phase
        final Runnable expireEvents =
                () -> executor.getListener().getShadowGraph().updateEventWindow(eventWindow);
        final SyncPhaseParallelExecutor parallelExecutor = new SyncPhaseParallelExecutor(
                getStaticThreadManager(),
                expireAfterPhase == 1 ? expireEvents : null,
                expireAfterPhase == 2 ? expireEvents : null,
                true);
        executor.setExecutorSupplier(() -> parallelExecutor);

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }

    /**
     * Tests sync with graphs where one node provides old events as other parents
     */
    @ParameterizedTest
    @MethodSource({"largeGraphParams"})
    void testNodeProvidingOldParents(final SyncTestParams params) throws Exception {
        runOldParentGraphTest(
                params,
                (factory) ->
                        // Set up the factory to create a custom event source for creator 0
                        factory.getSourceFactory()
                                .addCustomSource(
                                        (index) -> index == 0, () -> EventSourceFactory.newStandardEventSource()
                                                .setProvidedOtherParentAgeDistribution(integerPowerDistribution(0.2, 3))
                                                .setRecentEventRetentionSize(300)));
    }

    /**
     * Tests sync with graphs where one node provides old events as other parents
     */
    @ParameterizedTest
    @MethodSource({"largeGraphParams"})
    void testNodeAskingForOldParents(final SyncTestParams params) throws Exception {
        runOldParentGraphTest(
                params,
                (factory) ->
                        // Set up the factory to create a custom event source for creator 0
                        factory.getSourceFactory()
                                .addCustomSource(
                                        (index) -> index == 0, () -> EventSourceFactory.newStandardEventSource()
                                                .setRequestedOtherParentAgeDistribution(
                                                        integerPowerDistribution(0.02, 100))
                                                .setRecentEventRetentionSize(300)));
    }

    private void runOldParentGraphTest(final SyncTestParams params, final Consumer<EventEmitterFactory> factoryConfig)
            throws Exception {

        final SyncTestExecutor executor = new SyncTestExecutor(params);

        executor.setFactoryConfig(factoryConfig);

        executor.setCallerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(), 0, factory.newStandardFromSourceFactory(), params.getAncientMode()));
        executor.setListenerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                params.getNumNetworkNodes() - 1,
                factory.newStandardFromSourceFactory(),
                params.getAncientMode()));

        executor.setInitialGraphCreation((caller, listener) -> {
            for (final SyncNode node : List.of(caller, listener)) {
                node.generateAndAdd(params.getNumCommonEvents());
                node.setSaveGeneratedEvents(true);
            }
        });

        executor.execute();

        SyncValidator.assertOnlyRequiredEventsTransferred(
                executor.getCaller(), executor.getListener(), params.getAncientMode());
    }

    @ParameterizedTest
    @MethodSource("fourNodeGraphParams")
    void testConnectionClosedBehavior(final SyncTestParams params) {
        final SyncTestExecutor executor = new SyncTestExecutor(params);

        // after phase 2, close the connection
        final SyncPhaseParallelExecutor parallelExecutor = new SyncPhaseParallelExecutor(
                getStaticThreadManager(),
                null,
                () -> executor.getCaller().getConnection().disconnect(),
                false);

        executor.setCallerSupplier((factory) -> new SyncNode(
                params.getNumNetworkNodes(),
                0,
                factory.newShuffledFromSourceFactory(),
                parallelExecutor,
                params.getAncientMode()));

        try {
            executor.execute();
        } catch (Exception e) {
            final Exception syncException = executor.getCaller().getSyncException();
            Throwable wrappedException = (syncException != null) ? syncException.getCause() : null;
            while (wrappedException != null) {
                assertFalse(
                        NullPointerException.class.isAssignableFrom(wrappedException.getClass()),
                        "Should not have received a NullPointerException for a closed connection error.");
                wrappedException = wrappedException.getCause();
            }
            return;
        }
        fail("Expected an exception to be thrown when the connection broke.");
    }

    /**
     * Tests that a sync works if one node has no events at all in the graph and also has a non-ancient indicator that
     * is not 0
     */
    @ParameterizedTest
    @MethodSource("bothAncientModes")
    void noEventsStartIndicator(@NonNull final AncientMode ancientMode) throws Exception {
        final SyncTestParams params = new SyncTestParams(4, 0, 100, 0, ancientMode);
        final SyncTestExecutor executor = new SyncTestExecutor(params);
        executor.setGraphCustomization((caller, listener) -> {
            caller.setSaveGeneratedEvents(true);
            listener.setSaveGeneratedEvents(true);
        });
        executor.setEventWindowDefinitions((caller, listener) -> {
            final long callerMaximumIndicator =
                    SyncTestUtils.getMaxIndicator(caller.getShadowGraph().getTips(), ancientMode);
            final long callerAncientIndicator = SyncTestUtils.getMinIndicator(
                    caller.getShadowGraph()
                            .findAncestors(caller.getShadowGraph().getTips(), (e) -> true),
                    ancientMode);

            listener.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            ROUND_FIRST /* ignored */,
                            callerMaximumIndicator / 2,
                            callerAncientIndicator,
                            ancientMode));

            listener.getShadowGraph()
                    .updateEventWindow(new EventWindow(
                            ROUND_FIRST /* ignored */,
                            callerMaximumIndicator / 2,
                            callerAncientIndicator,
                            ancientMode));
        });
        executor.execute();
        SyncValidator.assertOnlyRequiredEventsTransferred(executor.getCaller(), executor.getListener(), ancientMode);
        SyncValidator.assertStreamsEmpty(executor.getCaller(), executor.getListener());
    }
}
