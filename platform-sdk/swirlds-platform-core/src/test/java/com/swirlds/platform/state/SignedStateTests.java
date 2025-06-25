// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.crypto.SignatureVerifier;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestMerkleStateRoot;
import com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import org.hiero.base.exceptions.ReferenceCountException;
import org.hiero.consensus.roster.RosterUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedState Tests")
class SignedStateTests {

    /**
     * Generate a signed state.
     */
    private SignedState generateSignedState(final Random random, final MerkleNodeState state) {
        return new RandomSignedStateGenerator(random)
                .setState(state)
                .buildWithFacade()
                .left();
    }

    @BeforeEach
    void setUp() {
        MerkleDb.resetDefaultInstancePath();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    /**
     * Build a mock state.
     *
     * @param reserveCallback this method is called when the State is reserved
     * @param releaseCallback this method is called when the State is released
     */
    private MerkleNodeState buildMockState(
            final Random random, final Runnable reserveCallback, final Runnable releaseCallback) {
        final var real = new TestMerkleStateRoot();
        TestingAppStateInitializer.DEFAULT.initStates(real);
        RosterUtils.setActiveRoster(real, RandomRosterBuilder.create(random).build(), 0L);
        final MerkleNodeState state = spy(real);
        if (reserveCallback != null) {
            doAnswer(invocation -> {
                        reserveCallback.run();
                        return null;
                    })
                    .when((MerkleNode) state)
                    .reserve();
        }

        if (releaseCallback != null) {
            doAnswer(invocation -> {
                        releaseCallback.run();
                        invocation.callRealMethod();
                        return null;
                    })
                    .when(state)
                    .release();
        }

        return state;
    }

    @Test
    @DisplayName("Reservation Test")
    void reservationTest() throws InterruptedException {
        final Random random = new Random();

        final AtomicBoolean reserved = new AtomicBoolean(false);
        final AtomicBoolean released = new AtomicBoolean(false);

        final MerkleNodeState state = buildMockState(
                random,
                () -> {
                    assertFalse(reserved.get(), "should only be reserved once");
                    reserved.set(true);
                },
                () -> {
                    assertFalse(released.get(), "should only be released once");
                    released.set(true);
                });

        final SignedState signedState = generateSignedState(random, state);

        final ReservedSignedState reservedSignedState;
        reservedSignedState = signedState.reserve("test");

        // Nothing should happen during this sleep, but give the background thread time to misbehave if it wants to
        MILLISECONDS.sleep(10);

        assertTrue(reserved.get(), "State should have been reserved");
        assertFalse(released.get(), "state should not be deleted");

        // Taking reservations should have no impact as long as we don't delete all of them
        final List<ReservedSignedState> reservations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            reservations.add(signedState.reserve("test"));
        }
        for (int i = 0; i < 10; i++) {
            reservations.get(i).close();
        }

        // Nothing should happen during this sleep, but give the background thread time to misbehave if it wants to
        MILLISECONDS.sleep(10);

        assertTrue(reserved.get(), "State should have been reserved");
        assertFalse(released.get(), "state should not be deleted");

        reservedSignedState.close();

        assertThrows(
                ReferenceCountException.class,
                () -> signedState.reserve("test"),
                "should not be able to reserve after full release");

        assertEventuallyTrue(released::get, Duration.ofSeconds(1), "state should eventually be released");
    }

    /**
     * Although this lifecycle is not expected in a real system, it's a nice for the sake of completeness to ensure that
     * a signed state can clean itself up without having an associated garbage collection thread.
     */
    @Test
    @DisplayName("No Garbage Collector Test")
    void noGarbageCollectorTest() {
        final Random random = new Random();

        final AtomicBoolean reserved = new AtomicBoolean(false);
        final AtomicBoolean archived = new AtomicBoolean(false);
        final AtomicBoolean released = new AtomicBoolean(false);

        final Thread mainThread = Thread.currentThread();

        final MerkleNodeState state = buildMockState(
                random,
                () -> {
                    assertFalse(reserved.get(), "should only be reserved once");
                    reserved.set(true);
                },
                () -> {
                    assertFalse(released.get(), "should only be released once");
                    assertSame(mainThread, Thread.currentThread(), "release should happen on main thread");
                    released.set(true);
                });

        final SignedState signedState = generateSignedState(random, state);

        final ReservedSignedState reservedSignedState = signedState.reserve("test");

        assertTrue(reserved.get(), "State should have been reserved");
        assertFalse(archived.get(), "state should not be archived");
        assertFalse(released.get(), "state should not be deleted");

        // Taking reservations should have no impact as long as we don't delete all of them
        final List<ReservedSignedState> reservations = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            reservations.add(signedState.reserve("test"));
        }
        for (int i = 0; i < 10; i++) {
            reservations.get(i).close();
        }

        assertTrue(reserved.get(), "State should have been reserved");
        assertFalse(archived.get(), "state should not be archived");
        assertFalse(released.get(), "state should not be deleted");

        reservedSignedState.close();

        assertThrows(
                ReferenceCountException.class,
                () -> signedState.reserve("test"),
                "should not be able to reserve after full release");

        assertEventuallyTrue(released::get, Duration.ofSeconds(1), "state should eventually be released");
        assertFalse(archived.get(), "state should not be archived");
    }

    /**
     * There used to be a bug (now fixed) that would case this test to fail.
     */
    @Test
    @DisplayName("Alternate Constructor Reservations Test")
    void alternateConstructorReservationsTest() {
        final MerkleNodeState state = spy(new TestMerkleStateRoot());
        final PlatformStateModifier platformState = mock(PlatformStateModifier.class);
        final TestPlatformStateFacade platformStateFacade = mock(TestPlatformStateFacade.class);
        TestingAppStateInitializer.DEFAULT.initPlatformState(state);
        when(platformState.getRound()).thenReturn(0L);
        final SignedState signedState = new SignedState(
                TestPlatformContextBuilder.create().build().getConfiguration(),
                mock(SignatureVerifier.class),
                state,
                "test",
                false,
                false,
                false,
                platformStateFacade);

        assertFalse(state.isDestroyed(), "state should not yet be destroyed");

        signedState.reserve("test").close();

        assertTrue(state.isDestroyed(), "state should now be destroyed");
    }
}
