// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.reconnect.impl;

import static org.hiero.base.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.VirtualMapLearner;
import com.swirlds.virtualmap.sync.LearningSynchronizer;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.state.signed.SigSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Tests how {@link ReconnectStateLearner} creates the learner, which copies the learner's data source, while it waits
 * for the teacher's signatures, and how it closes the learner if the reconnect fails before the synchronization.
 */
class ReconnectStateLearnerTest {

    private static final Duration RECONNECT_SOCKET_TIMEOUT = Duration.ofSeconds(5);

    /** How long a test waits for something that is expected to happen. */
    private static final long TIMEOUT_MILLIS = 5_000;

    // The teacher's signatures are written with the same connection type, which is incompatible with gzip
    private final Configuration configuration =
            new TestConfigBuilder().withValue("socket.gzipCompression", false).getOrCreateConfig();

    private PairedStreams pairedStreams;
    private DummyConnection teacherConnection;
    private DummyConnection learnerConnection;
    private VirtualMapState currentState;
    private LearningSynchronizer synchronizer;
    private VirtualMapLearner learner;
    /** Learner with {@code reconnect.copyLearnerStateEarly} enabled. */
    private ReconnectStateLearner reconnectLearner;

    @BeforeEach
    void setUp() throws IOException {
        pairedStreams = new PairedStreams();
        teacherConnection =
                new DummyConnection(configuration, pairedStreams.getTeacherInput(), pairedStreams.getTeacherOutput());
        learnerConnection = spy(
                new DummyConnection(configuration, pairedStreams.getLearnerInput(), pairedStreams.getLearnerOutput()));
        synchronizer = mock(LearningSynchronizer.class);
        learner = mock(VirtualMapLearner.class);

        currentState = mock(VirtualMapState.class);
        when(currentState.getRoot()).thenReturn(mock(VirtualMap.class));
        reconnectLearner = createReconnectLearner(true);
    }

    /**
     * Creates the learner under test.
     *
     * @param copyStateEarly the value of {@code reconnect.copyLearnerStateEarly}
     * @return the learner
     */
    private ReconnectStateLearner createReconnectLearner(final boolean copyStateEarly) {
        @SuppressWarnings("unchecked")
        final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager =
                mock(StateLifecycleManager.class);
        return new ReconnectStateLearner(
                new TestConfigBuilder()
                        .withValue("socket.gzipCompression", false)
                        .withValue("reconnect.copyLearnerStateEarly", copyStateEarly)
                        .getOrCreateConfig(),
                getStaticThreadManager(),
                learnerConnection,
                currentState,
                RECONNECT_SOCKET_TIMEOUT,
                mock(ReconnectMetrics.class),
                stateLifecycleManager,
                synchronizer);
    }

    @Test
    @DisplayName("By default, the learner isn't created if the signatures fail")
    void learnerNotCreatedIfSignaturesFailByDefault() {
        final ReconnectStateLearner defaultLearner = createReconnectLearner(false);
        // The teacher disconnects instead of sending the signatures
        pairedStreams.disconnectTeacher();

        assertThrows(RuntimeException.class, defaultLearner::execute);

        verify(synchronizer, never()).createLearner(any());
    }

    @Test
    @DisplayName("By default, the learner is created after the signatures, on the reconnect thread")
    void learnerCreatedAfterSignaturesByDefault() throws Exception {
        final ReconnectStateLearner defaultLearner = createReconnectLearner(false);
        final AtomicReference<Thread> creatingThread = new AtomicReference<>();
        when(synchronizer.createLearner(any())).thenAnswer(invocation -> {
            creatingThread.set(Thread.currentThread());
            return learner;
        });
        when(synchronizer.synchronize(
                        any(VirtualMapLearner.class), any(DataInputStream.class), any(DataOutputStream.class), any()))
                .thenThrow(new MerkleSynchronizationException("test failure"));
        sendSignatures();

        assertThrows(ReconnectStateException.class, defaultLearner::execute);

        assertSame(Thread.currentThread(), creatingThread.get(), "the learner should be created on this thread");
        final InOrder inOrder = inOrder(synchronizer);
        inOrder.verify(synchronizer).createLearner(any());
        inOrder.verify(synchronizer)
                .synchronize(same(learner), any(DataInputStream.class), any(DataOutputStream.class), any());
    }

    @AfterEach
    void tearDown() {
        // Not close(): it flushes buffered data, which fails if a test has already closed the sockets
        pairedStreams.disconnect();
    }

    @Test
    @DisplayName("The socket timeout is restored if the learner can't be started")
    void socketTimeoutRestoredIfLearnerCantBeStarted() throws Exception {
        final RuntimeException startFailure = new RuntimeException("test failure");
        when(currentState.getRoot()).thenThrow(startFailure);

        final RuntimeException e = assertThrows(RuntimeException.class, reconnectLearner::execute);

        assertSame(startFailure, e, "the reconnect should fail with the start failure");
        // Once to increase the timeout for the reconnect, and once to restore it
        verify(learnerConnection, times(2)).setTimeout(anyLong());
        verify(synchronizer, never()).createLearner(any());
    }

    @Test
    @DisplayName("A failure to create the learner fails the reconnect")
    void learnerCreationFailureFailsReconnect() throws Exception {
        final RuntimeException creationFailure = new RuntimeException("test failure");
        when(synchronizer.createLearner(any())).thenThrow(creationFailure);
        sendSignatures();

        final ReconnectStateException e = assertThrows(ReconnectStateException.class, reconnectLearner::execute);

        assertSame(creationFailure, e.getCause(), "the reconnect should fail with the creation failure");
        verifyNotSynchronized();
    }

    @Test
    @DisplayName("If the signatures fail, the learner disconnects and closes the learner once it's created")
    void signatureFailureClosesLearnerOnceCreated() throws Exception {
        final CountDownLatch creationAllowed = new CountDownLatch(1);
        when(synchronizer.createLearner(any())).thenAnswer(invocation -> {
            creationAllowed.await();
            return learner;
        });
        // The teacher disconnects instead of sending the signatures
        pairedStreams.disconnectTeacher();

        final Thread reconnectThread = startReconnect(new AtomicReference<>());
        // The reconnect disconnects right away, so that a healthy teacher doesn't wait for requests, and then waits
        // for the learner, so that a retry doesn't create a second one
        verify(learnerConnection, timeout(TIMEOUT_MILLIS)).disconnect();
        awaitWaiting(reconnectThread);
        verify(learner, never()).abortOnException();

        creationAllowed.countDown();
        reconnectThread.join(TIMEOUT_MILLIS);
        assertFalse(reconnectThread.isAlive(), "the reconnect should fail once the learner is created");
        final InOrder inOrder = inOrder(learnerConnection, learner);
        inOrder.verify(learnerConnection).disconnect();
        inOrder.verify(learner).abortOnException();
        verifyNotSynchronized();
    }

    @Test
    @DisplayName("If interrupted while waiting for the learner, the reconnect fails and closes the learner later")
    void interruptWhileWaitingForLearnerClosesLearnerLater() throws Exception {
        final CountDownLatch creationStarted = new CountDownLatch(1);
        final CountDownLatch creationAllowed = new CountDownLatch(1);
        when(synchronizer.createLearner(any())).thenAnswer(invocation -> {
            creationStarted.countDown();
            creationAllowed.await();
            return learner;
        });
        sendSignatures();

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread reconnectThread = startReconnect(failure);
        assertTrue(creationStarted.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS), "the learner should be created");
        reconnectThread.interrupt();

        // The reconnect doesn't wait for the learner after an interrupt
        reconnectThread.join(TIMEOUT_MILLIS);
        assertFalse(reconnectThread.isAlive(), "the reconnect should fail without waiting for the learner");
        assertInstanceOf(ReconnectStateException.class, failure.get());
        verify(learnerConnection).disconnect();
        verify(learner, never()).abortOnException();

        // The learner is closed when it's ready
        creationAllowed.countDown();
        verify(learner, timeout(TIMEOUT_MILLIS)).abortOnException();
        verifyNotSynchronized();
    }

    /** Sends the teacher's signatures, an empty signature set, to the learner. */
    private void sendSignatures() throws IOException {
        new SigSet().serialize(new WritableStreamingData(teacherConnection.getDos()));
        teacherConnection.getDos().flush();
    }

    /**
     * Runs the reconnect on a new thread.
     *
     * @param failure receives the failure of the reconnect, if any
     * @return the started thread
     */
    private Thread startReconnect(final AtomicReference<Throwable> failure) {
        final Thread thread = new Thread(() -> {
            try {
                reconnectLearner.execute();
            } catch (final Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        return thread;
    }

    /**
     * Waits until the thread is parked waiting for something, e.g. the learner.
     *
     * @param thread the thread
     */
    private static void awaitWaiting(final Thread thread) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (thread.getState() != Thread.State.WAITING) {
            assertTrue(System.currentTimeMillis() < deadline, "the thread should wait, but is " + thread.getState());
            Thread.sleep(10);
        }
    }

    private void verifyNotSynchronized() throws InterruptedException {
        verify(synchronizer, never())
                .synchronize(
                        any(VirtualMapLearner.class), any(DataInputStream.class), any(DataOutputStream.class), any());
    }
}
