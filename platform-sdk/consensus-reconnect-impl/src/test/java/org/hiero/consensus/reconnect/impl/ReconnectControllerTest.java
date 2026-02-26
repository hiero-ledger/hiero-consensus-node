// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static com.swirlds.platform.test.fixtures.state.TestStateUtils.destroyStateLifecycleManager;
import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.signed.SignedStateValidationData;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.base.concurrent.test.fixtures.RunnableCompletionControl;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SigSet;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * Comprehensive unit-integration test for {@link ReconnectController}.
 * Tests focus on retry logic, promise lifecycle, state transitions, and error handling.
 */
class ReconnectControllerTest {

    private static final long WEIGHT_PER_NODE = 100L;
    private static final int NUM_NODES = 4;
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(3);

    private Configuration configuration;
    private Roster roster;
    private Platform platform;
    private ReconnectCoordinator reconnectCoordinator;
    private StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;
    private SavedStateController savedStateController;
    private ConsensusStateEventHandler consensusStateEventHandler;
    private BlockingResourceProvider<ReservedSignedStateResult> stateProvider;
    private FallenBehindMonitor fallenBehindMonitor;
    private NodeId selfId;

    private SignedState testSignedState;
    private ReservedSignedState testReservedSignedState;
    private VirtualMapState testWorkingState;
    private SignedStateValidator signedStateValidator;

    @AfterAll
    static void tearDownClass() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @BeforeEach
    void setUp() {
        final Random random = getRandomPrintSeed();

        // Create roster
        roster = RandomRosterBuilder.create(random)
                .withSize(NUM_NODES)
                .withWeightGenerator(
                        (l, i) -> WeightGenerators.balancedNodeWeights(NUM_NODES, WEIGHT_PER_NODE * NUM_NODES))
                .build();

        selfId = NodeId.of(0);

        // Create platform context with reconnect enabled
        configuration = new TestConfigBuilder()
                .withValue("reconnect.active", true)
                .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", 5)
                .withValue("reconnect.minimumTimeBetweenReconnects", "100ms")
                .withValue("reconnect.reconnectWindowSeconds", -1) // disabled
                .getOrCreateConfig();

        stateLifecycleManager = new VirtualMapStateLifecycleManager(new NoOpMetrics(), new FakeTime(), configuration);
        stateLifecycleManager.initWithState(createTestState());
        // Create test states
        testSignedState = new RandomSignedStateGenerator(random)
                .setRoster(roster)
                .setState(stateLifecycleManager.getMutableState())
                .build();
        SignedStateFileReader.registerServiceStates(testSignedState);
        final SigSet sigSet = new SigSet();

        roster.rosterEntries()
                .forEach(rosterEntry -> sigSet.addSignature(NodeId.of(rosterEntry.nodeId()), randomSignature(random)));

        testSignedState.setSigSet(sigSet);

        testWorkingState = stateLifecycleManager.getMutableState();
        testReservedSignedState = testSignedState.reserve("test");

        // Mock Platform
        platform = mock(Platform.class);

        // Mock ReconnectCoordinator
        reconnectCoordinator = mock(ReconnectCoordinator.class);

        // Create real FallenBehindMonitor (needs to be created before setting up coordinator mock)
        fallenBehindMonitor = new FallenBehindMonitor(NUM_NODES - 1, 0.5);

        // Configure platformCoordinator.pauseGossip() to call fallenBehindMonitor.notifySyncProtocolPaused()
        doAnswer(inv -> {
                    fallenBehindMonitor.notifySyncProtocolPaused();
                    return null;
                })
                .when(reconnectCoordinator)
                .pauseGossip();

        // Mock SavedStateController
        savedStateController = mock(SavedStateController.class);

        // Mock ConsensusStateEventHandler
        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);

        // Create real state provider for peer reconnect
        stateProvider = new BlockingResourceProvider<>();

        // Create the signed state validator
        signedStateValidator = mock(SignedStateValidator.class);
    }

    @AfterEach
    void tearDown() {
        if (testWorkingState != null) {
            testWorkingState.release();
        }
        if (testReservedSignedState != null && !testReservedSignedState.isClosed()) {
            testReservedSignedState.close();
        }
        destroyStateLifecycleManager(stateLifecycleManager);
    }

    /**
     * Helper method to create a ReconnectController instance
     */
    private ReconnectController createController() {
        return new ReconnectController(
                configuration,
                Time.getCurrent(),
                roster,
                platform,
                reconnectCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                stateProvider,
                selfId,
                fallenBehindMonitor,
                signedStateValidator);
    }
    /**
     * Helper method to create a ReconnectController instance
     */
    private ReconnectController createController(@NonNull final Configuration configuration, @NonNull final Time time) {
        return new ReconnectController(
                configuration,
                time,
                roster,
                platform,
                reconnectCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                stateProvider,
                selfId,
                fallenBehindMonitor,
                signedStateValidator);
    }

    /**
     * Test scenario runner that abstracts away all threading complexity.
     * Use this to write readable tests that focus on the reconnect flow logic.
     */
    private class ReconnectScenario {
        private final ReconnectController controller;
        private Thread controllerThread;
        private RunnableCompletionControl controllerRunnable;
        private final List<RunnableCompletionControl> parallelTasks = new ArrayList<>();

        /** Default timeout for wait operations */
        private static final Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(5);

        ReconnectScenario(final ReconnectController controller) {
            this.controller = controller;
        }

        /** Start the reconnect controller in a background thread (normal behavior, no exception throwing) */
        ReconnectScenario start() {
            return startWithExitCapture(new AtomicReference<>());
        }

        /** Start the controller with SystemExit mocking to capture exit codes */
        ReconnectScenario startWithExitCapture(AtomicReference<?> capturedExitCode) {
            controllerRunnable = RunnableCompletionControl.unblocked(() -> {
                try (@SuppressWarnings("unused")
                                final var ignored = mockStatic(SignedStateFileReader.class);
                        final var systemExitMock = mockStatic(SystemExitUtils.class)) {
                    systemExitMock
                            .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                            .thenAnswer(inv -> {
                                capturedExitCode.set(inv.getArgument(0));
                                // Throw exception to simulate system exit and break out of control flow
                                throw new RuntimeException("Simulated System Exit: " + inv.getArgument(0));
                            });
                    controller.run();
                } catch (RuntimeException e) {
                    // Expected exception from mocked system exit - this is normal
                    if (!e.getMessage().startsWith("Simulated System Exit")) {
                        throw e; // Re-throw if it's not our simulated exit
                    }
                }
            });
            controllerThread = controllerRunnable.start();
            return this;
        }

        /** Report a single node as fallen behind (may not reach threshold) */
        ReconnectScenario reportFallenBehind(final NodeId... nodeIds) {
            Arrays.asList(nodeIds).forEach(fallenBehindMonitor::report);
            return this;
        }

        /** Provide a valid reconnect state using the test's default reserved state */
        ReconnectScenario provideState() throws InterruptedException {
            return provideState(testReservedSignedState);
        }

        /** Provide a specific reconnect state to the controller */
        ReconnectScenario provideState(final ReservedSignedState state) throws InterruptedException {
            assertTrue(stateProvider.acquireProvidePermit(), "Should acquire permit");
            stateProvider.provide(new ReservedSignedStateResult(state, null));
            return this;
        }

        /** Provide an exception instead of a state */
        ReconnectScenario provideException(final RuntimeException exception) throws InterruptedException {
            assertTrue(stateProvider.acquireProvidePermit(), "Should acquire permit");
            stateProvider.provide(new ReservedSignedStateResult(null, exception));
            return this;
        }

        /**
         * Waits for reconnect processing to request a state.
         *
         * @return this scenario for method chaining
         * @throws AssertionError if timeout expires before state is requested
         */
        ReconnectScenario waitForReconnectToRequestState() {
            return waitForCondition(
                    () -> !stateProvider.isWaitingForResource(),
                    "Reconnect did not request state within " + DEFAULT_WAIT_TIMEOUT);
        }

        /**
         * Waits for reconnect processing to receive a state.
         *
         * @return this scenario for method chaining
         * @throws AssertionError if timeout expires before state is requested
         */
        ReconnectScenario waitForReconnectToReceiveState() {
            return waitForCondition(
                    () -> stateProvider.isWaitingForResource(),
                    "Reconnect was not provided with a state within " + DEFAULT_WAIT_TIMEOUT);
        }

        /**
         * Waits for a condition to become true.
         *
         * @return this scenario for method chaining
         * @throws AssertionError if timeout expires before the condition is met
         */
        ReconnectScenario waitForCondition(final Supplier<Boolean> condition, final String timeoutMessage) {
            final long startTime = System.currentTimeMillis();
            final long timeoutMillis = ReconnectScenario.DEFAULT_WAIT_TIMEOUT.toMillis();

            while (condition.get()) {
                final long elapsed = System.currentTimeMillis() - startTime;
                if (elapsed >= timeoutMillis) {
                    fail(String.format(
                            "%s. Waited %dms. Controller state: isAlive=%s, isWaiting=%s",
                            timeoutMessage, elapsed, isControllerAlive(), stateProvider.isWaitingForResource()));
                }
                sleep(100);
            }
            return this;
        }

        /** Stop the controller gracefully*/
        void stop(final Duration timeout, final String failureMessage) {
            controller.stopReconnectLoop();
            interruptControllerThread();
            waitFor(() -> parallelTasks.forEach(r -> r.waitIsFinished(timeout)), failureMessage);
            waitFor(() -> controllerRunnable.waitIsFinished(timeout), failureMessage);
        }

        /**
         * Wait for a condition to become true with custom timeout.
         *
         * @throws AssertionError if timeout expires before condition becomes true
         */
        private void waitFor(Runnable runnable, String failureMessage) {
            try {
                runnable.run();
            } catch (Exception e) {
                if (e.getMessage().startsWith("Timed out")) {
                    fail(failureMessage);
                }
                throw e;
            }
        }

        void interruptControllerThread() {
            controllerThread.interrupt();
        }

        /** Wait for controller to finish (without stopping it first)
         * @param timeout*/
        void waitForFinish(final Duration timeout) {
            waitFor(() -> controllerRunnable.waitIsFinished(timeout), "Wait for finish timed out elapsed");
            waitFor(() -> parallelTasks.forEach(r -> r.waitIsFinished(timeout)), "Wait for finish timed out elapsed");
        }

        /** Execute a custom action during the scenario */
        ReconnectScenario syncRun(final ThrowingRunnable action) {
            try {
                action.run();
            } catch (InterruptedException e) {
                fail();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return this;
        }

        /** Wait for a specified duration (milliseconds) */
        ReconnectScenario wait(final int millis) {
            sleep(millis);
            return this;
        }

        /** Check if controller thread is alive */
        boolean isControllerAlive() {
            return controllerThread.isAlive();
        }

        public ReconnectScenario parallelRun(final Runnable... runnable) {
            final List<RunnableCompletionControl> list = Arrays.stream(runnable)
                    .map(RunnableCompletionControl::unblocked)
                    .toList();
            list.forEach(RunnableCompletionControl::start);
            parallelTasks.addAll(list);
            return this;
        }
    }

    @Test
    @DisplayName("Multiple peers try to provide")
    void testMultiplePeersReportBeforeThreshold() {
        final var scenario = new ReconnectScenario(createController());
        final AtomicLong counter = new AtomicLong();
        final Runnable peer = () -> {
            if (stateProvider.acquireProvidePermit()) {
                counter.incrementAndGet();
                try {
                    stateProvider.provide(new ReservedSignedStateResult(testReservedSignedState, null));
                } catch (InterruptedException e) {
                    fail();
                }
            }
        };
        scenario.start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .parallelRun(peer, peer, peer, peer)
                .waitForReconnectToReceiveState()
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");

        assertEquals(1, counter.get());
    }

    @Test
    @DisplayName("Successful single reconnect attempt")
    void testSuccessfulSingleReconnect() throws InterruptedException {

        new ReconnectControllerTest.ReconnectScenario(createController())
                .start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .provideState()
                .waitForReconnectToReceiveState()
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");

        // Verify the expected interactions
        verify(reconnectCoordinator, times(1)).submitStatusAction(any(FallenBehindAction.class));
        verify(reconnectCoordinator, times(1)).pauseGossip();
        verify(reconnectCoordinator, atLeast(1)).clear();
        verify(reconnectCoordinator, times(1)).loadReconnectState(any(), any());
        verify(reconnectCoordinator, times(1)).submitStatusAction(any(ReconnectCompleteAction.class));
        verify(reconnectCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("Promise is properly cleaned up after consumption")
    void testPromiseCleanupAfterConsumption() throws InterruptedException {
        new ReconnectScenario(createController())
                .start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .provideState()
                .syncRun(() -> {
                    // After consumption, the promise should not allow new acquires (consumed)
                    assertFalse(stateProvider.acquireProvidePermit());
                })
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");
    }

    @Test
    @DisplayName("State validation failure causes retry")
    void testStateValidationFailureCausesRetry() {
        // Create a new context with a validator that will fail once then succeed
        final AtomicInteger validationAttempts = new AtomicInteger(0);

        // Mock the validator by making consensusStateEventHandler throw on first call
        doAnswer((Answer<Void>) invocation -> {
                    if (validationAttempts.incrementAndGet() == 1) {
                        throw new RuntimeException("Simulated validation failure");
                    }
                    return null;
                })
                .when(consensusStateEventHandler)
                .onStateInitialized(any(), any(), any(), any());

        new ReconnectScenario(createController())
                .start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .syncRun(() -> {
                    try {
                        // First attempt - will fail validation
                        assertTrue(stateProvider.acquireProvidePermit(), "Should acquire permit");
                        stateProvider.provide(new ReservedSignedStateResult(testSignedState.reserve("first"), null));
                    } catch (InterruptedException e) {
                        fail();
                    }
                })
                .waitForReconnectToRequestState()
                .syncRun(() -> {
                    try {
                        // Second attempt - will succeed
                        assertTrue(stateProvider.acquireProvidePermit(), "Should acquire permit");
                        stateProvider.provide(new ReservedSignedStateResult(testSignedState.reserve("second"), null));
                    } catch (InterruptedException e) {
                        fail();
                    }
                })
                .waitForReconnectToReceiveState()
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");

        assertEquals(2, validationAttempts.get(), "Should have attempted validation twice");
        verify(reconnectCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("Providing exception causes retry")
    void testProvidingAnExceptionCausesRetry() throws InterruptedException {
        new ReconnectScenario(createController())
                .start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .provideException(new RuntimeException("simulated exception"))
                .waitForReconnectToRequestState()
                .provideState(testSignedState.reserve("second"))
                .waitForReconnectToReceiveState()
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");
    }

    @Test
    @DisplayName("FallenBehindMonitor is reset after successful reconnect")
    void testFallenBehindMonitorReset() throws InterruptedException {
        new ReconnectScenario(createController())
                .start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .provideState()
                .waitForReconnectToReceiveState()
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");

        // Verify monitor was reset after successful reconnect
        assertFalse(
                fallenBehindMonitor.hasFallenBehind(),
                "FallenBehindMonitor should be reset after successful reconnect");
    }

    @Test
    @DisplayName("Coordinator operations are called in correct order")
    void testCoordinatorOperationsOrder() throws InterruptedException {
        final AtomicReference<String> operationOrder = new AtomicReference<>("");

        // Track operation order
        doAnswer(inv -> {
                    operationOrder.updateAndGet(s -> s + "pauseGossip,");
                    fallenBehindMonitor.notifySyncProtocolPaused();
                    return null;
                })
                .when(reconnectCoordinator)
                .pauseGossip();

        doAnswer(inv -> {
                    operationOrder.updateAndGet(s -> s + "clear,");
                    return null;
                })
                .when(reconnectCoordinator)
                .clear();

        doAnswer(inv -> {
                    operationOrder.updateAndGet(s -> s + "resumeGossip,");
                    return null;
                })
                .when(reconnectCoordinator)
                .resumeGossip();

        new ReconnectScenario(createController())
                .start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .provideState()
                .waitForReconnectToReceiveState()
                .stop(LONG_TIMEOUT, "Controller did not finished when expected");

        // Verify operations occurred in correct order
        final String operations = operationOrder.get();
        assertTrue(operations.contains("pauseGossip"), "Should pause gossip");
        assertTrue(operations.contains("clear"), "Should clear queues");
        assertTrue(operations.contains("resumeGossip"), "Should resume gossip");

        final int pauseIndex = operations.indexOf("pauseGossip");
        final int resumeIndex = operations.indexOf("resumeGossip");
        assertTrue(pauseIndex < resumeIndex, "pauseGossip should come before resumeGossip");
    }

    @Test
    @DisplayName("ReconnectCompleteAction is submitted with correct round")
    void testReconnectCompleteActionSubmitted() throws InterruptedException {
        final ReconnectController controller = createController();
        final AtomicReference<ReconnectCompleteAction> capturedAction = new AtomicReference<>();

        // Capture the submitted action
        doAnswer(inv -> {
                    final Object arg = inv.getArgument(0);
                    if (arg instanceof ReconnectCompleteAction action) {
                        capturedAction.set(action);
                    }
                    return null;
                })
                .when(reconnectCoordinator)
                .submitStatusAction(any());

        final var scenario = new ReconnectScenario(controller);
        scenario.start()
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .provideState()
                .waitForReconnectToReceiveState()
                .syncRun(() -> {
                    controller.stopReconnectLoop();
                    scenario.interruptControllerThread();
                })
                .waitForFinish(LONG_TIMEOUT);

        // Verify the action was submitted with correct round
        assertNotNull(capturedAction.get(), "ReconnectCompleteAction should have been submitted");
        assertEquals(
                testSignedState.getRound(),
                capturedAction.get().reconnectStateRound(),
                "Action should have correct round");
    }

    @Test
    @DisplayName("System exits when maximum reconnect failures threshold is exceeded")
    void testSystemExitOnMaxReconnectFailures() {
        // Mock the validator to throw on first call, succeed on second
        doAnswer(a -> {
                    throw new IllegalStateException("Simulated validation failure");
                })
                .when(signedStateValidator)
                .validate(any(SignedState.class), any(Roster.class), any(SignedStateValidationData.class));

        final ReconnectController controller = createController();
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        new ReconnectScenario(controller)
                .startWithExitCapture(capturedExitCode)
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .syncRun(() -> {
                    try {
                        // Simulate 5 failed reconnect attempts (matching maximumReconnectFailuresBeforeShutdown)
                        for (int i = 0; i < 5; i++) {
                            sleep(500);
                            Assertions.assertTrue(stateProvider.acquireProvidePermit());
                            stateProvider.provide(
                                    new ReservedSignedStateResult(testSignedState.reserve("retry" + i), null));
                        }
                    } catch (InterruptedException e) {
                        Assertions.fail();
                    }
                })
                .waitForReconnectToReceiveState()
                .waitForFinish(LONG_TIMEOUT);

        // Verify the correct exit code was captured
        assertNotNull(capturedExitCode.get(), "SystemExitUtils.exitSystem should have been called");
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(), "Should exit with RECONNECT_FAILURE code");
    }

    @Test
    @DisplayName("System exits when reconnect window has elapsed")
    void testSystemExitOnReconnectWindowTimeout() {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();

        // Create a platform context with a very short reconnect window (1 second)
        final Configuration shortWindowContext = new TestConfigBuilder()
                .withValue("reconnect.active", true)
                .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", 5)
                .withValue("reconnect.minimumTimeBetweenReconnects", "100ms")
                .withValue("reconnect.reconnectWindowSeconds", 1) // 1 second window
                .getOrCreateConfig();
        final FakeTime fakeTime = new FakeTime();

        new ReconnectScenario(createController(shortWindowContext, fakeTime))
                .startWithExitCapture(capturedExitCode)
                .syncRun(() -> {
                    // make the time move forward for the window to elapse
                    fakeTime.tick(Duration.ofSeconds(2));
                })
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .wait(1000)
                .waitForFinish(LONG_TIMEOUT);

        // Verify the correct exit code was captured
        assertNotNull(capturedExitCode.get(), "SystemExitUtils.exitSystem should have been called when window elapsed");
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(), "Should exit with RECONNECT_FAILURE code");
    }

    @Test
    @DisplayName("System exits when reconnect is disabled")
    void testSystemExitWhenReconnectDisabled() {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();

        // Create a platform context with reconnect disabled
        final Configuration disabledContext = new TestConfigBuilder()
                .withValue("reconnect.active", false) // Disabled
                .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", 5)
                .withValue("reconnect.minimumTimeBetweenReconnects", "100ms")
                .withValue("reconnect.reconnectWindowSeconds", -1)
                .getOrCreateConfig();

        final ReconnectController controller = createController(disabledContext, Time.getCurrent());

        new ReconnectScenario(controller)
                .startWithExitCapture(capturedExitCode)
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForFinish(LONG_TIMEOUT);

        // Verify the correct exit code was captured
        assertNotNull(
                capturedExitCode.get(),
                "SystemExitUtils.exitSystem should have been called when reconnect is disabled");
    }

    @Test
    @DisplayName("System exits on unexpected runtime exception during reconnect")
    void testSystemExitOnUnexpectedRuntimeException() {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();

        // Make platformCoordinator.pauseGossip() throw an unexpected RuntimeException
        doThrow(new RuntimeException("Unexpected error during pauseGossip"))
                .when(reconnectCoordinator)
                .pauseGossip();

        new ReconnectScenario(createController())
                .startWithExitCapture(capturedExitCode)
                // Start controller
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForFinish(LONG_TIMEOUT);

        // Verify the correct exit code was captured
        assertNotNull(
                capturedExitCode.get(), "SystemExitUtils.exitSystem should have been called on unexpected exception");
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE,
                capturedExitCode.get(),
                "Should exit with RECONNECT_FAILURE code on unexpected exception");
    }

    @Test
    @DisplayName("System exits on unexpected InterruptedException during reconnect")
    void testSystemExitOnUnexpectedInterruptedExceptionDuringReconnect() {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();

        final ReconnectController controller = createController();
        final var scenario = new ReconnectScenario(controller);
        scenario.startWithExitCapture(capturedExitCode)
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .syncRun(scenario::interruptControllerThread)
                .waitForFinish(LONG_TIMEOUT);

        // Verify the correct exit code was captured
        assertNotNull(
                capturedExitCode.get(), "SystemExitUtils.exitSystem should have been called on InterruptedException");
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE,
                capturedExitCode.get(),
                "Should exit with RECONNECT_FAILURE code on InterruptedException");
    }

    @Test
    @DisplayName("Controller gracefully stops when interrupted during operations after stop()")
    void controllerGracefullyStopsWhenStopReconnectLoopIsCalledAndThreadIsInterrupted() {
        final AtomicReference<?> systemExitCalled = new AtomicReference<>();

        final ReconnectController controller = createController();

        final var scenario = new ReconnectScenario(controller);
        scenario
                // Start controller
                .startWithExitCapture(systemExitCalled)
                .reportFallenBehind(NodeId.of(1), NodeId.of(2))
                .waitForReconnectToRequestState()
                .syncRun(() -> {
                    controller.stopReconnectLoop();
                    scenario.interruptControllerThread();
                })
                .waitForFinish(LONG_TIMEOUT);

        // Wait for system exit to be called
        assertNull(
                systemExitCalled.get(),
                "SystemExitUtils.exitSystem should not have been called on expected InterruptedException");

        // Verify the thread finished correctly
        assertFalse(scenario.isControllerAlive());
    }

    private static void sleep(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            fail("Unexpected interruption", e);
        }
    }
}
