// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.state.test.fixtures.merkle.VirtualMapStateTestUtils.createTestState;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.network.protocol.ReservedSignedStateResultPromise;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SigSet;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateValidationData;
import com.swirlds.platform.state.signed.SignedStateValidator;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.SystemExitCode;
import com.swirlds.platform.system.SystemExitUtils;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.wiring.PlatformCoordinator;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.state.StateLifecycleManager;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.concurrent.test.fixtures.RunnableCompletionControl;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.Answer;

/**
 * Comprehensive unit-integration test for {@link ReconnectController}.
 * Tests focus on retry logic, promise lifecycle, state transitions, and error handling.
 */
class ReconnectControllerTest {

    private static final long WEIGHT_PER_NODE = 100L;
    private static final int NUM_NODES = 4;
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(3);

    private PlatformContext platformContext;
    private Roster roster;
    private MerkleCryptography merkleCryptography;
    private Platform platform;
    private PlatformCoordinator platformCoordinator;
    private StateLifecycleManager stateLifecycleManager;
    private SavedStateController savedStateController;
    private ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private ReservedSignedStateResultPromise peerReservedSignedStateResultPromise;
    private FallenBehindMonitor fallenBehindMonitor;
    private NodeId selfId;

    private SignedState testSignedState;
    private ReservedSignedState testReservedSignedState;
    private MerkleNodeState testWorkingState;
    private SignedStateValidator signedStateValidator;
    private MockedStatic<SignedStateFileReader> mockedFileReader;

    @BeforeAll
    static void setUpClass() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("com.swirlds.platform.state");
        registry.registerConstructables("com.swirlds.platform.state.signed");
        registry.registerConstructables("com.swirlds.platform.system");
        registry.registerConstructables("com.swirlds.state.merkle");
    }

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
        platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue("reconnect.active", true)
                        .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", 5)
                        .withValue("reconnect.minimumTimeBetweenReconnects", "100ms")
                        .withValue("reconnect.reconnectWindowSeconds", -1) // disabled
                        .getOrCreateConfig())
                .build();

        // Create test states
        testSignedState = new RandomSignedStateGenerator()
                .setRoster(roster)
                .setState(createTestState())
                .build();
        SignedStateFileReader.registerServiceStates(testSignedState);
        final SigSet sigSet = new SigSet();

        roster.rosterEntries()
                .forEach(rosterEntry -> sigSet.addSignature(NodeId.of(rosterEntry.nodeId()), randomSignature(random)));

        testSignedState.setSigSet(sigSet);

        testWorkingState = testSignedState.getState().copy();
        testReservedSignedState = testSignedState.reserve("test");

        // Mock MerkleCryptography
        merkleCryptography = mock(MerkleCryptography.class);
        final CompletableFuture<Hash> mockHashFuture = CompletableFuture.completedFuture(new Hash());
        when(merkleCryptography.digestTreeAsync(any())).thenReturn(mockHashFuture);

        // Mock Platform
        platform = mock(Platform.class);

        // Mock PlatformCoordinator
        platformCoordinator = mock(PlatformCoordinator.class);

        // Mock SwirldStateManager
        stateLifecycleManager = mock(StateLifecycleManager.class);
        when(stateLifecycleManager.getMutableState()).thenReturn(testWorkingState);

        // Mock SavedStateController
        savedStateController = mock(SavedStateController.class);

        // Mock ConsensusStateEventHandler
        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);

        // Create real FallenBehindMonitor
        fallenBehindMonitor = new FallenBehindMonitor(NUM_NODES - 1, 0.5);

        // Create real ReservedSignedStatePromise
        peerReservedSignedStateResultPromise = new ReservedSignedStateResultPromise();

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
        if (mockedFileReader != null) {
            mockedFileReader.close();
        }
    }

    /**
     * Helper method to create a ReconnectController instance
     */
    private ReconnectController createController() {
        return new ReconnectController(
                roster,
                merkleCryptography,
                platform,
                platformContext,
                platformCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                peerReservedSignedStateResultPromise,
                selfId,
                fallenBehindMonitor,
                signedStateValidator);
    }

    @Test
    @DisplayName("Successful single reconnect attempt")
    void testSuccessfulSingleReconnect() throws Exception {

        final ReconnectController controller = createController();
        final AtomicBoolean reconnectCompleted = new AtomicBoolean(false);

        // Start controller in a separate thread
        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var staticMock = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });
        // Start a thread to simulate the reconnect flow
        final var notifier = RunnableCompletionControl.unblocked(() -> {
            // Simulate fallen behind notification
            fallenBehindMonitor.report(NodeId.of(1));
            fallenBehindMonitor.report(NodeId.of(2));
        });
        notifier.start();
        final var simulatorThread = RunnableCompletionControl.unblocked(() -> {
            try {
                Thread.sleep(50);
                // Acquire permit and provide state
                assertTrue(peerReservedSignedStateResultPromise.acquire(), "Should acquire permit");
                peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);

                // Wait a bit to ensure reconnect completes
                Thread.sleep(200);
                reconnectCompleted.set(true);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        notifier.waitIsFinished(LONG_TIMEOUT);
        simulatorThread.start();

        // Wait for both threads
        simulatorThread.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        assertTrue(reconnectCompleted.get(), "Reconnect should have completed");

        // Verify the expected interactions
        verify(platformCoordinator, times(1)).submitStatusAction(any(FallenBehindAction.class));
        verify(platformCoordinator, times(1)).pauseGossip();
        verify(platformCoordinator, atLeast(1)).clear();
        verify(platformCoordinator, times(1)).loadReconnectState(any(), any());
        verify(platformCoordinator, times(1)).submitStatusAction(any(ReconnectCompleteAction.class));
        verify(platformCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("Promise is properly cleaned up after consumption")
    void testPromiseCleanupAfterConsumption() throws Exception {
        final ReconnectController controller = createController();
        final CountDownLatch stateProvidedLatch = new CountDownLatch(1);
        final AtomicBoolean stateAcquired = new AtomicBoolean(false);

        // Start controller
        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var notifier = RunnableCompletionControl.unblocked(() -> {
            fallenBehindMonitor.report(NodeId.of(1));
            fallenBehindMonitor.report(NodeId.of(2));
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {

                stateAcquired.set(peerReservedSignedStateResultPromise.acquire());
                if (!stateAcquired.get()) {
                    return;
                }
                peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        controllerRunnable.start();
        notifier.start();
        notifier.waitIsFinished(LONG_TIMEOUT);
        Thread.sleep(200); // Give enough time for reconnec controller to start
        simulator.start();
        simulator.waitIsFinished(LONG_TIMEOUT);
        assertTrue(stateAcquired.get(), "acquire should succeed");

        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        simulator.start();
        simulator.waitIsFinished(LONG_TIMEOUT);
        // After consumption, the promise should not allow new acquires (consumed)
        // Note: This depends on the implementation of BlockingResourceProvider
        assertFalse(stateAcquired.get(), "Second acquire should fail after promise consumed");
    }

    @Test
    @DisplayName("Controller stops when stop() is called")
    void testControllerStopReconnectLoop() throws Exception {
        final ReconnectController controller = createController();

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var staticMock = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var controllerThread = controllerRunnable.start();

        // Give controller time to start
        Thread.sleep(100);

        // Stop the controller
        controller.stopReconnectLoop();
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);
        Thread.sleep(100);
        assertFalse(controllerThread.isAlive(), "Controller thread should be terminated");
    }

    @Test
    @DisplayName("State validation failure causes retry")
    void testStateValidationFailureCausesRetry() throws Exception {
        // Create a new context with a validator that will fail once then succeed
        final AtomicInteger validationAttempts = new AtomicInteger(0);
        final ReconnectController controller = createController();

        // Mock the validator by making consensusStateEventHandler throw on first call
        doAnswer((Answer<Void>) invocation -> {
                    if (validationAttempts.incrementAndGet() == 1) {
                        throw new RuntimeException("Simulated validation failure");
                    }
                    return null;
                })
                .when(consensusStateEventHandler)
                .onStateInitialized(any(), any(), any(), any());

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var staticMock = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var notifier = RunnableCompletionControl.unblocked(() -> {
            fallenBehindMonitor.report(NodeId.of(1));
            fallenBehindMonitor.report(NodeId.of(2));
        });

        notifier.start();
        notifier.waitIsFinished(LONG_TIMEOUT);

        final var simulatorThread = RunnableCompletionControl.unblocked(() -> {
            try {
                // First attempt - will fail validation
                Thread.sleep(100);
                assertTrue(peerReservedSignedStateResultPromise.acquire());
                final ReservedSignedState firstAttempt = testSignedState.reserve("first");
                peerReservedSignedStateResultPromise.resolveWithValue(firstAttempt);

                // Second attempt - will succeed
                Thread.sleep(250);
                assertTrue(peerReservedSignedStateResultPromise.acquire());
                final ReservedSignedState secondAttempt = testSignedState.reserve("second");
                peerReservedSignedStateResultPromise.resolveWithValue(secondAttempt);

                Thread.sleep(200);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        simulatorThread.start();

        simulatorThread.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        assertEquals(2, validationAttempts.get(), "Should have attempted validation twice");
        verify(platformCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("System exits when Hash current state for reconnect throws ExecutionException")
    void testHashStateExecutionException() throws Exception {
        final CompletableFuture<Hash> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Hash computation failed"));
        when(merkleCryptography.digestTreeAsync(any())).thenReturn(failedFuture);

        final ReconnectController controller = createController();
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);
        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            capturedExitCode.set(inv.getArgument(0));
                            exitCalledLatch.countDown();
                            return null;
                        });
                controller.run();
            }
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {

                // Trigger fallen behind
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));
                // Wait for hash to fail and controller to be ready for next attempt
                Thread.sleep(150);

                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        simulator.start();
        simulator.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);
        // Wait for system exit to be called (should happen immediately in start())
        assertTrue(
                exitCalledLatch.await(2, SECONDS),
                "SystemExitUtils.exitSystem should have been called when reconnect is disabled");

        // Verify the correct exit code
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(), "Should exit with RECONNECT_FAILURE code");
    }

    @Test
    @DisplayName("Multiple peers report fallen behind before threshold")
    void testMultiplePeersReportBeforeThreshold() throws Exception {
        final ReconnectController controller = createController();
        final CountDownLatch reconnectStartedLatch = new CountDownLatch(1);

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var staticMock = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {
                // Report just below threshold (need >50% of 3 peers = need at least 2)
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));

                // Wait a bit - reconnect should not start yet
                Thread.sleep(100);
                assertFalse(fallenBehindMonitor.hasFallenBehind());

                // Now push over threshold
                fallenBehindMonitor.report(NodeId.of(2));
                Thread.sleep(50);
                assertTrue(fallenBehindMonitor.hasFallenBehind());

                reconnectStartedLatch.countDown();

                // Provide state
                Thread.sleep(100);
                if (peerReservedSignedStateResultPromise.acquire()) {
                    peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);
                }

                Thread.sleep(200);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        simulator.start();

        assertTrue(reconnectStartedLatch.await(2, SECONDS), "Reconnect should have started");

        simulator.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        verify(platformCoordinator, times(1)).submitStatusAction(any(FallenBehindAction.class));
    }

    @Test
    @DisplayName("FallenBehindMonitor is reset after successful reconnect")
    void testFallenBehindMonitorReset() throws Exception {
        final ReconnectController controller = createController();
        final AtomicBoolean monitorWasReset = new AtomicBoolean(false);

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var notifier = RunnableCompletionControl.unblocked(() -> {
            // First reconnect cycle
            fallenBehindMonitor.report(NodeId.of(1));
            fallenBehindMonitor.report(NodeId.of(2));
            assertTrue(fallenBehindMonitor.hasFallenBehind());
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {

                Thread.sleep(100);
                assertTrue(peerReservedSignedStateResultPromise.acquire());
                peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);

                // Wait for reconnect to complete
                Thread.sleep(300);

                // Check if monitor was reset
                monitorWasReset.set(!fallenBehindMonitor.hasFallenBehind());

                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        notifier.start();
        notifier.waitIsFinished(LONG_TIMEOUT);
        simulator.start();

        simulator.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        assertTrue(monitorWasReset.get(), "FallenBehindMonitor should be reset after successful reconnect");
    }

    @Test
    @DisplayName("Coordinator operations are called in correct order")
    void testCoordinatorOperationsOrder() throws Exception {
        final ReconnectController controller = createController();
        final AtomicReference<String> operationOrder = new AtomicReference<>("");

        doAnswer(inv -> {
                    operationOrder.updateAndGet(s -> s + "pauseGossip,");
                    return null;
                })
                .when(platformCoordinator)
                .pauseGossip();

        doAnswer(inv -> {
                    operationOrder.updateAndGet(s -> s + "clear,");
                    return null;
                })
                .when(platformCoordinator)
                .clear();

        doAnswer(inv -> {
                    operationOrder.updateAndGet(s -> s + "resumeGossip,");
                    return null;
                })
                .when(platformCoordinator)
                .resumeGossip();

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                Thread.sleep(100);
                assertTrue(peerReservedSignedStateResultPromise.acquire());
                peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);

                Thread.sleep(200);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        simulator.start();

        simulator.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        final String operations = operationOrder.get();
        assertTrue(operations.contains("pauseGossip"), "Should pause gossip");
        assertTrue(operations.contains("clear"), "Should clear queues");
        assertTrue(operations.contains("resumeGossip"), "Should resume gossip");

        // Verify pauseGossip comes before resumeGossip
        final int pauseIndex = operations.indexOf("pauseGossip");
        final int resumeIndex = operations.indexOf("resumeGossip");
        assertTrue(pauseIndex < resumeIndex, "pauseGossip should come before resumeGossip");
    }

    @Test
    @DisplayName("SavedStateController is notified of received state")
    void testSavedStateControllerNotified() throws Exception {
        final ReconnectController controller = createController();

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                Thread.sleep(100);
                assertTrue(peerReservedSignedStateResultPromise.acquire());
                peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);

                Thread.sleep(200);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        simulator.start();

        simulator.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        verify(savedStateController, times(1)).reconnectStateReceived(any(ReservedSignedState.class));
    }

    @Test
    @DisplayName("ReconnectCompleteAction is submitted with correct round")
    void testReconnectCompleteActionSubmitted() throws Exception {
        final ReconnectController controller = createController();
        final AtomicReference<ReconnectCompleteAction> capturedAction = new AtomicReference<>();

        doAnswer(inv -> {
                    final Object arg = inv.getArgument(0);
                    if (arg instanceof ReconnectCompleteAction action) {
                        capturedAction.set(action);
                    }
                    return null;
                })
                .when(platformCoordinator)
                .submitStatusAction(any());

        final var controllerRunnable = RunnableCompletionControl.unblocked(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class)) {
                controller.run();
            }
        });

        final var simulator = RunnableCompletionControl.unblocked(() -> {
            try {
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                Thread.sleep(100);
                assertTrue(peerReservedSignedStateResultPromise.acquire());
                peerReservedSignedStateResultPromise.resolveWithValue(testReservedSignedState);

                Thread.sleep(200);
                controller.stopReconnectLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        final var controllerThread = controllerRunnable.start();
        simulator.start();

        simulator.waitIsFinished(LONG_TIMEOUT);
        controllerThread.interrupt();
        controllerRunnable.waitIsFinished(LONG_TIMEOUT);

        assertNotNull(capturedAction.get(), "ReconnectCompleteAction should have been submitted");
        assertEquals(
                testSignedState.getRound(),
                capturedAction.get().reconnectStateRound(),
                "Action should have correct round");
    }

    @Test
    @DisplayName("System exits when maximum reconnect failures threshold is exceeded")
    void testSystemExitOnMaxReconnectFailures() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);
        final Semaphore signedStateIvoked = new Semaphore(0);
        // Mock the validator to throw on first call, succeed on second
        doAnswer(a -> {
                    signedStateIvoked.release();
                    throw new IllegalStateException("Simulated validation failure");
                })
                .when(signedStateValidator)
                .validate(any(SignedState.class), any(Roster.class), any(SignedStateValidationData.class));

        final ReconnectController controller = createController();

        final Thread controllerThread = new Thread(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            capturedExitCode.set(inv.getArgument(0));
                            exitCalledLatch.countDown();
                            return null;
                        });
                controller.run();
            }
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // Simulate 5 failed reconnect attempts (matching maximumReconnectFailuresBeforeShutdown)
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(500);
                    assertTrue(peerReservedSignedStateResultPromise.acquire());
                    peerReservedSignedStateResultPromise.resolveWithValue(testSignedState.reserve("retry" + i));
                    signedStateIvoked.acquire();
                }

                // Wait a bit longer for the exit to be called
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(1000);
        simulatorThread.join(1000);
        // Wait for system exit to be called
        assertTrue(exitCalledLatch.await(5, SECONDS), "SystemExitUtils.exitSystem should have been called");

        // Verify the correct exit code
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(), "Should exit with RECONNECT_FAILURE code");
    }

    @Test
    @DisplayName("System exits when reconnect window has elapsed")
    void testSystemExitOnReconnectWindowTimeout() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        // Create a platform context with a very short reconnect window (1 second)
        final PlatformContext shortWindowContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue("reconnect.active", true)
                        .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", 5)
                        .withValue("reconnect.minimumTimeBetweenReconnects", "100ms")
                        .withValue("reconnect.reconnectWindowSeconds", 1) // 1 second window
                        .getOrCreateConfig())
                .withTime(new FakeTime())
                .build();

        final ReconnectController controller = new ReconnectController(
                roster,
                merkleCryptography,
                platform,
                shortWindowContext,
                platformCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                peerReservedSignedStateResultPromise,
                selfId,
                fallenBehindMonitor,
                signedStateValidator);

        final Thread controllerThread = new Thread(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            capturedExitCode.set(inv.getArgument(0));
                            exitCalledLatch.countDown();
                            return null;
                        });
                controller.run();
            }
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // make the time move forward for the window to elapse
                ((FakeTime) shortWindowContext.getTime()).tick(Duration.ofSeconds(2));

                // Now trigger fallen behind (after window has elapsed)
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // Wait for exit to be called
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        // Wait for system exit to be called
        assertTrue(
                exitCalledLatch.await(3, SECONDS),
                "SystemExitUtils.exitSystem should have been called when window elapsed");

        // Verify the correct exit code
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(), "Should exit with RECONNECT_FAILURE code");

        controllerThread.join(1000);
        simulatorThread.join(1000);
    }

    @Test
    @DisplayName("System exits when reconnect is disabled")
    void testSystemExitWhenReconnectDisabled() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        // Create a platform context with reconnect disabled
        final PlatformContext disabledContext = TestPlatformContextBuilder.create()
                .withConfiguration(new TestConfigBuilder()
                        .withValue("reconnect.active", false) // Disabled
                        .withValue("reconnect.maximumReconnectFailuresBeforeShutdown", 5)
                        .withValue("reconnect.minimumTimeBetweenReconnects", "100ms")
                        .withValue("reconnect.reconnectWindowSeconds", -1)
                        .getOrCreateConfig())
                .build();

        final ReconnectController controller = new ReconnectController(
                roster,
                merkleCryptography,
                platform,
                disabledContext,
                platformCoordinator,
                stateLifecycleManager,
                savedStateController,
                consensusStateEventHandler,
                peerReservedSignedStateResultPromise,
                selfId,
                fallenBehindMonitor,
                signedStateValidator);

        final Thread controllerThread = new Thread(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            capturedExitCode.set(inv.getArgument(0));
                            exitCalledLatch.countDown();
                            return null;
                        });
                controller.run();
            }
        });

        controllerThread.start();
        // Trigger fallen behind
        fallenBehindMonitor.report(NodeId.of(1));
        fallenBehindMonitor.report(NodeId.of(2));

        // Wait for system exit to be called (should happen immediately in start())
        assertTrue(
                exitCalledLatch.await(2, SECONDS),
                "SystemExitUtils.exitSystem should have been called when reconnect is disabled");

        // Verify the correct exit code
        assertEquals(
                SystemExitCode.BEHIND_RECONNECT_DISABLED,
                capturedExitCode.get(),
                "Should exit with BEHIND_RECONNECT_DISABLED code");

        controllerThread.join(1000);
    }

    @Test
    @DisplayName("System exits on unexpected runtime exception during reconnect")
    void testSystemExitOnUnexpectedRuntimeException() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        // Make platformCoordinator.pauseGossip() throw an unexpected RuntimeException
        doThrow(new RuntimeException("Unexpected error during pauseGossip"))
                .when(platformCoordinator)
                .pauseGossip();

        final ReconnectController controller = createController();

        final Thread controllerThread = new Thread(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            capturedExitCode.set(inv.getArgument(0));
                            exitCalledLatch.countDown();
                            return null;
                        });
                controller.run();
            }
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // Wait for the exception to occur and exit to be called
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        // Wait for system exit to be called
        assertTrue(
                exitCalledLatch.await(2, SECONDS),
                "SystemExitUtils.exitSystem should have been called on unexpected exception");

        // Verify the correct exit code
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE,
                capturedExitCode.get(),
                "Should exit with RECONNECT_FAILURE code on unexpected exception");

        controllerThread.join(1000);
        simulatorThread.join(1000);
    }

    @Test
    @DisplayName("System exits on unexpected InterruptedException during reconnect")
    void testSystemExitOnUnexpectedInterruptedExceptionDuringReconnect() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        final ReconnectController controller = createController();

        final Thread controllerThread = new Thread(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            capturedExitCode.set(inv.getArgument(0));
                            exitCalledLatch.countDown();
                            return null;
                        });
                controller.run();
            }
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));
                // Give enough time for the reconnect thread to wait on a state to be provided
                Thread.sleep(2000);

                // Cause an unexpected interruption on the reconnectThread
                controllerThread.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(3000);
        simulatorThread.join(3000);

        // Wait for system exit to be called
        assertTrue(
                exitCalledLatch.await(2, SECONDS),
                "SystemExitUtils.exitSystem should have been called on InterruptedException");

        // Verify the correct exit code
        assertEquals(
                SystemExitCode.RECONNECT_FAILURE,
                capturedExitCode.get(),
                "Should exit with RECONNECT_FAILURE code on InterruptedException");
    }

    @Test
    @DisplayName("Controller gracefully stops when interrupted during operations after stop()")
    void controllerGracefullyStopsWhenStopReconnectLoopIsCalledAndThreadIsInterrupted() throws Exception {
        final AtomicBoolean systemExitCalled = new AtomicBoolean(false);

        final ReconnectController controller = createController();

        final Thread controllerThread = new Thread(() -> {
            try (final var ignored = mockStatic(SignedStateFileReader.class);
                    final var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
                mockedSystemExit
                        .when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                        .thenAnswer(inv -> {
                            systemExitCalled.set(true);
                            return null;
                        });
                controller.run();
            }
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));
                // Give enough time for the reconnect thread to wait on a state to be provided
                Thread.sleep(2000);
                // Cause an expected interruption on the reconnectThread
                controller.stopReconnectLoop();
                controllerThread.interrupt();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(3000);
        simulatorThread.join(3000);

        // Wait for system exit to be called
        assertFalse(
                systemExitCalled.get(),
                "SystemExitUtils.exitSystem should not have been called on expected InterruptedException");

        // Verify the thread finished correctly
        assertFalse(controllerThread.isAlive());
    }
}
