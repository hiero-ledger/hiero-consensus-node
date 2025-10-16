// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hiero.base.crypto.test.fixtures.CryptoRandomUtils.randomSignature;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.network.protocol.ReservedSignedStatePromise;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.SwirldStateManager;
import com.swirlds.platform.state.service.PlatformStateFacade;
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
import com.swirlds.state.test.fixtures.merkle.TestVirtualMapState;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Comprehensive unit-integration test for {@link ReconnectController}.
 * Tests focus on retry logic, promise lifecycle, state transitions, and error handling.
 */
class ReconnectControllerTest {

    private static final long WEIGHT_PER_NODE = 100L;
    private static final int NUM_NODES = 4;
    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);
    private static final Duration MEDIUM_TIMEOUT = Duration.ofMillis(500);
    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(3);

    private PlatformContext platformContext;
    private PlatformStateFacade platformStateFacade;
    private Roster roster;
    private MerkleCryptography merkleCryptography;
    private Platform platform;
    private PlatformCoordinator platformCoordinator;
    private SwirldStateManager swirldStateManager;
    private SavedStateController savedStateController;
    private ConsensusStateEventHandler<MerkleNodeState> consensusStateEventHandler;
    private ReservedSignedStatePromise peerReservedSignedStatePromise;
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
                .withWeightGenerator((l, i) -> WeightGenerators.balancedNodeWeights(NUM_NODES, WEIGHT_PER_NODE * NUM_NODES))
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
        final var signedStatePair = new RandomSignedStateGenerator()
                .setRoster(roster)
                .setState(new TestVirtualMapState())
                .buildWithFacade();
        testSignedState = signedStatePair.left();
        testSignedState.init(platformContext);
        SignedStateFileReader.registerServiceStates(testSignedState);
        final SigSet sigSet = new SigSet();

        roster.rosterEntries().forEach(rosterEntry -> sigSet.addSignature(NodeId.of(rosterEntry.nodeId()), randomSignature(random)));

        testSignedState.setSigSet(sigSet);


        platformStateFacade = signedStatePair.right();
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
        swirldStateManager = mock(SwirldStateManager.class);
        when(swirldStateManager.getConsensusState()).thenReturn(testWorkingState);

        // Mock SavedStateController
        savedStateController = mock(SavedStateController.class);

        // Mock ConsensusStateEventHandler
        consensusStateEventHandler = mock(ConsensusStateEventHandler.class);

        // Create real FallenBehindMonitor
        fallenBehindMonitor = new FallenBehindMonitor(NUM_NODES - 1, 0.5);

        // Create real ReservedSignedStatePromise
        peerReservedSignedStatePromise = new ReservedSignedStatePromise();

        //Create the signed state validator
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
                platformStateFacade,
                roster,
                merkleCryptography,
                platform,
                platformContext,
                platformCoordinator,
                swirldStateManager,
                savedStateController,
                consensusStateEventHandler,
                peerReservedSignedStatePromise,
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
            final Thread controllerThread = new Thread(()->{
                try(final var staticMock = mockStatic(SignedStateFileReader.class)) {
                    controller.start();}
                }
            );
            // Start a thread to simulate the reconnect flow
            final Thread simulatorThread = new Thread(() -> {
                try {
                    // Simulate fallen behind notification
                    Thread.sleep(50);
                    fallenBehindMonitor.report(NodeId.of(1));
                    fallenBehindMonitor.report(NodeId.of(2));
                    Thread.sleep(50);

                    // Acquire permit and provide state
                    assertTrue(peerReservedSignedStatePromise.acquire(), "Should acquire permit");
                    peerReservedSignedStatePromise.provide(testReservedSignedState);

                    // Wait a bit to ensure reconnect completes
                    Thread.sleep(200);
                    reconnectCompleted.set(true);
                    controller.stop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            controllerThread.start();
            simulatorThread.start();

            // Wait for both threads
            controllerThread.join(LONG_TIMEOUT.toMillis());
            simulatorThread.join(LONG_TIMEOUT.toMillis());

            assertTrue(reconnectCompleted.get(), "Reconnect should have completed");

            // Verify the expected interactions
            verify(platformCoordinator, times(1)).submitStatusAction(any(FallenBehindAction.class));
            verify(platformCoordinator, times(1)).pauseGossip();
            verify(platformCoordinator, atLeast(1)).clear();
            verify(merkleCryptography, times(1)).digestTreeAsync(any());
            verify(swirldStateManager, times(1)).loadFromSignedState(any());
            verify(platformCoordinator, times(1)).submitStatusAction(any(ReconnectCompleteAction.class));
            verify(platformCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("Reconnect with retries - fails then succeeds")
    void testReconnectWithRetries() throws Exception {
        final ReconnectController controller = createController();
        final AtomicInteger attemptCount = new AtomicInteger(0);
        final AtomicInteger validationAttempts = new AtomicInteger(3);
        final int failuresBeforeSuccess = 3;
        // Mock the validator to throw on first call, succeed on second
        doAnswer(invocation -> {
            if (validationAttempts.decrementAndGet()> 0) {
                throw new IllegalStateException("Simulated validation failure");
            }
            return null; // void method, so return null on success
        }).when(signedStateValidator).validate(any(SignedState.class), any(Roster.class), any(SignedStateValidationData.class));

        // Start controller in a separate thread
        final Thread controllerThread = new Thread(()->{
            try(final var staticMock = mockStatic(SignedStateFileReader.class)) {
                controller.start();}
        }
        );

        // Start a thread to simulate multiple reconnect attempts
        final Thread simulatorThread = new Thread(() -> {
            try {
                // Simulate fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // Final successful attempt
                Thread.sleep(150);
                assertTrue(peerReservedSignedStatePromise.acquire(), "Should acquire permit for successful attempt");
                attemptCount.incrementAndGet();
                peerReservedSignedStatePromise.provide(testReservedSignedState);

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

        assertEquals(failuresBeforeSuccess + 1, attemptCount.get(), "Should have made correct number of attempts");

        // Verify multiple clear calls (one per failed attempt)
        verify(platformCoordinator, atLeast(failuresBeforeSuccess + 1)).clear();
        verify(platformCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("Promise is properly cleaned up after consumption")
    void testPromiseCleanupAfterConsumption() throws Exception {
        final ReconnectController controller = createController();
        final CountDownLatch stateProvidedLatch = new CountDownLatch(1);
        final CountDownLatch reconnectCompleteLatch = new CountDownLatch(1);
        final AtomicBoolean secondAcquireFailed = new AtomicBoolean(false);

        // Start controller
        final Thread controllerThread = new Thread(controller::start);

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // First reconnect - acquire and provide
                Thread.sleep(100);
                assertTrue(peerReservedSignedStatePromise.acquire(), "First acquire should succeed");
                peerReservedSignedStatePromise.provide(testReservedSignedState);
                stateProvidedLatch.countDown();

                // Wait for reconnect to complete
                Thread.sleep(200);

                // Try to acquire again - should fail because promise was consumed
                secondAcquireFailed.set(!peerReservedSignedStatePromise.acquire());
                reconnectCompleteLatch.countDown();

                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        assertTrue(stateProvidedLatch.await(2, SECONDS), "State should have been provided");
        assertTrue(reconnectCompleteLatch.await(2, SECONDS), "Reconnect should have completed");

        controllerThread.join(1000);
        simulatorThread.join(1000);

        // After consumption, the promise should not allow new acquires (consumed)
        // Note: This depends on the implementation of BlockingResourceProvider
        assertTrue(secondAcquireFailed.get(), "Second acquire should fail after promise consumed");
    }

    @Test
    @DisplayName("Controller stops when stop() is called")
    void testControllerStop() throws Exception {
        final ReconnectController controller = createController();
        final AtomicBoolean controllerExited = new AtomicBoolean(false);

        final Thread controllerThread = new Thread(() -> {
            controller.start();
            controllerExited.set(true);
        });

        controllerThread.start();

        // Give controller time to start
        Thread.sleep(100);

        // Stop the controller
        controller.stop();

        controllerThread.join(1000);

        assertTrue(controllerExited.get(), "Controller should have exited");
        assertFalse(controllerThread.isAlive(), "Controller thread should be terminated");
    }

    @Test
    @DisplayName("State validation failure causes retry")
    void testStateValidationFailureCausesRetry() throws Exception {
        // Create a new context with a validator that will fail once then succeed
        final AtomicInteger validationAttempts = new AtomicInteger(0);
        final ReconnectController controller = createController();

        // Mock the validator by making consensusStateEventHandler throw on first call
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                if (validationAttempts.incrementAndGet() == 1) {
                    throw new RuntimeException("Simulated validation failure");
                }
                return null;
            }
        }).when(consensusStateEventHandler).onStateInitialized(any(), any(), any(), any());

        final Thread controllerThread = new Thread(() -> {
            controller.start();
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // First attempt - will fail validation
                Thread.sleep(100);
                assertTrue(peerReservedSignedStatePromise.acquire());
                final ReservedSignedState firstAttempt = testSignedState.reserve("first");
                peerReservedSignedStatePromise.provide(firstAttempt);

                // Second attempt - will succeed
                Thread.sleep(250);
                assertTrue(peerReservedSignedStatePromise.acquire());
                final ReservedSignedState secondAttempt = testSignedState.reserve("second");
                peerReservedSignedStatePromise.provide(secondAttempt);

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

        assertEquals(2, validationAttempts.get(), "Should have attempted validation twice");
        verify(platformCoordinator, times(1)).resumeGossip();
    }

    @Test
    @DisplayName("Hash state for reconnect handles ExecutionException")
    void testHashStateExecutionException() throws Exception {
        final CompletableFuture<Hash> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Hash computation failed"));
        when(merkleCryptography.digestTreeAsync(any())).thenReturn(failedFuture);

        final ReconnectController controller = createController();

        final Thread controllerThread = new Thread(() -> {
            controller.start();
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                // Trigger fallen behind
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                // Wait for hash to fail and controller to be ready for next attempt
                Thread.sleep(150);

                // Fix the hash for next attempt
                final CompletableFuture<Hash> successFuture = CompletableFuture.completedFuture(new Hash());
                when(merkleCryptography.digestTreeAsync(any())).thenReturn(successFuture);

                // Release first attempt
                if (peerReservedSignedStatePromise.acquire()) {
                    peerReservedSignedStatePromise.release();
                }

                Thread.sleep(150);

                // Provide successful state
                if (peerReservedSignedStatePromise.acquire()) {
                    peerReservedSignedStatePromise.provide(testReservedSignedState);
                }

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

        // Verify that hashing was attempted at least twice
        verify(merkleCryptography, atLeast(2)).digestTreeAsync(any());
    }

    @Test
    @DisplayName("Multiple peers report fallen behind before threshold")
    void testMultiplePeersReportBeforeThreshold() throws Exception {
        final ReconnectController controller = createController();
        final CountDownLatch reconnectStartedLatch = new CountDownLatch(1);

        final Thread controllerThread = new Thread(() -> {
            controller.start();
        });

        final Thread simulatorThread = new Thread(() -> {
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
                if (peerReservedSignedStatePromise.acquire()) {
                    peerReservedSignedStatePromise.provide(testReservedSignedState);
                }

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        assertTrue(reconnectStartedLatch.await(2, SECONDS), "Reconnect should have started");

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

        verify(platformCoordinator, times(1)).submitStatusAction(any(FallenBehindAction.class));
    }

    @Test
    @DisplayName("FallenBehindMonitor is reset after successful reconnect")
    void testFallenBehindMonitorReset() throws Exception {
        final ReconnectController controller = createController();
        final AtomicBoolean monitorWasReset = new AtomicBoolean(false);

        final Thread controllerThread = new Thread(controller::start);

        final Thread simulatorThread = new Thread(() -> {
            try {
                // First reconnect cycle
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));
                assertTrue(fallenBehindMonitor.hasFallenBehind());

                Thread.sleep(100);
                assertTrue(peerReservedSignedStatePromise.acquire());
                peerReservedSignedStatePromise.provide(testReservedSignedState);

                // Wait for reconnect to complete
                Thread.sleep(300);

                // Check if monitor was reset
                monitorWasReset.set(!fallenBehindMonitor.hasFallenBehind());

                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

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
        }).when(platformCoordinator).pauseGossip();

        doAnswer(inv -> {
            operationOrder.updateAndGet(s -> s + "clear,");
            return null;
        }).when(platformCoordinator).clear();

        doAnswer(inv -> {
            operationOrder.updateAndGet(s -> s + "resumeGossip,");
            return null;
        }).when(platformCoordinator).resumeGossip();

        final Thread controllerThread = new Thread(() -> {
            controller.start();
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                Thread.sleep(100);
                assertTrue(peerReservedSignedStatePromise.acquire());
                peerReservedSignedStatePromise.provide(testReservedSignedState);

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

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

        final Thread controllerThread = new Thread(() -> {
            controller.start();
        });

        final Thread simulatorThread = new Thread(() -> {
            try {
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                Thread.sleep(100);
                assertTrue(peerReservedSignedStatePromise.acquire());
                peerReservedSignedStatePromise.provide(testReservedSignedState);

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

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
        }).when(platformCoordinator).submitStatusAction(any());

        final Thread controllerThread = new Thread(controller::start);

        final Thread simulatorThread = new Thread(() -> {
            try {
                Thread.sleep(50);
                fallenBehindMonitor.report(NodeId.of(1));
                fallenBehindMonitor.report(NodeId.of(2));

                Thread.sleep(100);
                assertTrue(peerReservedSignedStatePromise.acquire());
                peerReservedSignedStatePromise.provide(testReservedSignedState);

                Thread.sleep(200);
                controller.stop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        controllerThread.start();
        simulatorThread.start();

        controllerThread.join(LONG_TIMEOUT.toMillis());
        simulatorThread.join(LONG_TIMEOUT.toMillis());

        assertNotNull(capturedAction.get(), "ReconnectCompleteAction should have been submitted");
        assertEquals(testSignedState.getRound(), capturedAction.get().reconnectStateRound(), "Action should have correct round");
    }

    @Test
    @DisplayName("Thread interruption during await is handled")
    void testThreadInterruptionHandling() throws Exception {
        final ReconnectController controller = createController();
        final AtomicBoolean interruptionHandled = new AtomicBoolean(false);

        final Thread controllerThread = new Thread(() -> {
            try {
                controller.start();
            } catch (RuntimeException e) {
                if (e.getCause() instanceof InterruptedException) {
                    interruptionHandled.set(true);
                }
            }
        });

        controllerThread.start();

        // Give controller time to start and wait
        Thread.sleep(100);

        // Trigger fallen behind so controller enters await
        fallenBehindMonitor.report(NodeId.of(1));
        fallenBehindMonitor.report(NodeId.of(2));

        Thread.sleep(100);

        // Interrupt the controller thread
        controllerThread.interrupt();

        controllerThread.join(1000);

        // The controller should have exited (either by interruption handling or normal flow)
        assertFalse(controllerThread.isAlive(), "Controller thread should have terminated");
    }

    @Test
    @DisplayName("System exits when maximum reconnect failures threshold is exceeded")
    void testSystemExitOnMaxReconnectFailures() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        try (var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
            mockedSystemExit.when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                    .thenAnswer(inv -> {
                        capturedExitCode.set(inv.getArgument(0));
                        exitCalledLatch.countDown();
                        return null;
                    });

            final ReconnectController controller = createController();

            final Thread controllerThread = new Thread(controller::start);

            final Thread simulatorThread = new Thread(() -> {
                try {
                    // Trigger fallen behind
                    Thread.sleep(50);
                    fallenBehindMonitor.report(NodeId.of(1));
                    fallenBehindMonitor.report(NodeId.of(2));

                    // Simulate 5 failed reconnect attempts (matching maximumReconnectFailuresBeforeShutdown)
                    for (int i = 0; i < 5; i++) {
                        Thread.sleep(150);
                        if (peerReservedSignedStatePromise.acquire()) {
                            peerReservedSignedStatePromise.release();
                        }
                    }

                    // Wait a bit longer for the exit to be called
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            controllerThread.start();
            simulatorThread.start();

            // Wait for system exit to be called
            assertTrue(exitCalledLatch.await(5, SECONDS), "SystemExitUtils.exitSystem should have been called");

            // Verify the correct exit code
            assertEquals(SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(),
                    "Should exit with RECONNECT_FAILURE code");

            controllerThread.join(1000);
            simulatorThread.join(1000);

            mockedSystemExit.verify(() -> SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE), times(1));
        }
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
                .build();

        try (var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
            mockedSystemExit.when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                    .thenAnswer(inv -> {
                        capturedExitCode.set(inv.getArgument(0));
                        exitCalledLatch.countDown();
                        return null;
                    });

            final ReconnectController controller = new ReconnectController(
                    platformStateFacade,
                    roster,
                    merkleCryptography,
                    platform,
                    shortWindowContext,
                    platformCoordinator,
                    swirldStateManager,
                    savedStateController,
                    consensusStateEventHandler,
                    peerReservedSignedStatePromise,
                    selfId,
                    fallenBehindMonitor, new DefaultSignedStateValidator(shortWindowContext, platformStateFacade));

            final Thread controllerThread = new Thread(controller::start);

            final Thread simulatorThread = new Thread(() -> {
                try {
                    // Wait for window to elapse
                    Thread.sleep(1100);

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
            assertTrue(exitCalledLatch.await(3, SECONDS),
                    "SystemExitUtils.exitSystem should have been called when window elapsed");

            // Verify the correct exit code
            assertEquals(SystemExitCode.BEHIND_RECONNECT_DISABLED, capturedExitCode.get(),
                    "Should exit with BEHIND_RECONNECT_DISABLED code");

            controllerThread.join(1000);
            simulatorThread.join(1000);

            mockedSystemExit.verify(() -> SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED),
                    times(1));
        }
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

        try (var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
            mockedSystemExit.when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                    .thenAnswer(inv -> {
                        capturedExitCode.set(inv.getArgument(0));
                        exitCalledLatch.countDown();
                        return null;
                    });

            final ReconnectController controller = new ReconnectController(
                    platformStateFacade,
                    roster,
                    merkleCryptography,
                    platform,
                    disabledContext,
                    platformCoordinator,
                    swirldStateManager,
                    savedStateController,
                    consensusStateEventHandler,
                    peerReservedSignedStatePromise,
                    selfId,
                    fallenBehindMonitor, new DefaultSignedStateValidator(disabledContext, platformStateFacade));

            final Thread controllerThread = new Thread(controller::start);

            controllerThread.start();

            // Wait for system exit to be called (should happen immediately in start())
            assertTrue(exitCalledLatch.await(2, SECONDS),
                    "SystemExitUtils.exitSystem should have been called when reconnect is disabled");

            // Verify the correct exit code
            assertEquals(SystemExitCode.BEHIND_RECONNECT_DISABLED, capturedExitCode.get(),
                    "Should exit with BEHIND_RECONNECT_DISABLED code");

            controllerThread.join(1000);

            mockedSystemExit.verify(() -> SystemExitUtils.exitSystem(SystemExitCode.BEHIND_RECONNECT_DISABLED),
                    times(1));
        }
    }

    @Test
    @DisplayName("System exits on unexpected runtime exception during reconnect")
    void testSystemExitOnUnexpectedRuntimeException() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        // Make platformCoordinator.pauseGossip() throw an unexpected RuntimeException
        doThrow(new RuntimeException("Unexpected error during pauseGossip"))
                .when(platformCoordinator).pauseGossip();

        try (var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
            mockedSystemExit.when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                    .thenAnswer(inv -> {
                        capturedExitCode.set(inv.getArgument(0));
                        exitCalledLatch.countDown();
                        return null;
                    });

            final ReconnectController controller = createController();

            final Thread controllerThread = new Thread(controller::start);

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
            assertTrue(exitCalledLatch.await(2, SECONDS),
                    "SystemExitUtils.exitSystem should have been called on unexpected exception");

            // Verify the correct exit code
            assertEquals(SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(),
                    "Should exit with RECONNECT_FAILURE code on unexpected exception");

            controllerThread.join(1000);
            simulatorThread.join(1000);

            mockedSystemExit.verify(() -> SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE), times(1));
        }
    }

    @Test
    @DisplayName("System exits on InterruptedException during reconnect")
    void testSystemExitOnInterruptedExceptionDuringReconnect() throws Exception {
        final AtomicReference<SystemExitCode> capturedExitCode = new AtomicReference<>();
        final CountDownLatch exitCalledLatch = new CountDownLatch(1);

        // Make swirldStateManager.getConsensusState() throw InterruptedException wrapped in RuntimeException
        when(swirldStateManager.getConsensusState()).thenAnswer(inv -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException(new InterruptedException("Simulated interruption"));
        });

        try (var mockedSystemExit = mockStatic(SystemExitUtils.class)) {
            mockedSystemExit.when(() -> SystemExitUtils.exitSystem(any(SystemExitCode.class)))
                    .thenAnswer(inv -> {
                        capturedExitCode.set(inv.getArgument(0));
                        exitCalledLatch.countDown();
                        return null;
                    });

            final ReconnectController controller = createController();

            final Thread controllerThread = new Thread(controller::start);

            final Thread simulatorThread = new Thread(() -> {
                try {
                    // Trigger fallen behind
                    Thread.sleep(50);
                    fallenBehindMonitor.report(NodeId.of(1));
                    fallenBehindMonitor.report(NodeId.of(2));

                    // Wait for the exception to occur
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            controllerThread.start();
            simulatorThread.start();

            // Wait for system exit to be called
            assertTrue(exitCalledLatch.await(2, SECONDS),
                    "SystemExitUtils.exitSystem should have been called on InterruptedException");

            // Verify the correct exit code
            assertEquals(SystemExitCode.RECONNECT_FAILURE, capturedExitCode.get(),
                    "Should exit with RECONNECT_FAILURE code on InterruptedException");

            controllerThread.join(1000);
            simulatorThread.join(1000);

            mockedSystemExit.verify(() -> SystemExitUtils.exitSystem(SystemExitCode.RECONNECT_FAILURE), times(1));
        }
    }
}