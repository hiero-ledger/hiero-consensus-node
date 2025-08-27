// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.platform.state.signed.ReservedSignedState.createNullReservation;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import org.hiero.base.exceptions.ReferenceCountException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ReservedSignedState Tests")
class ReservedSignedStateTests {

    @BeforeEach
    void setUp() {
        MerkleDb.resetDefaultInstancePath();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("Null State Test")
    void nullStateTest() {
        try (final ReservedSignedState reservedSignedState = createNullReservation()) {
            assertThrows(IllegalStateException.class, reservedSignedState::get);
            assertNull(reservedSignedState.getNullable());
            assertEquals("", reservedSignedState.getReason());
            assertTrue(reservedSignedState.isNull());
            assertFalse(reservedSignedState.isNotNull());

            try (final ReservedSignedState reservedSignedState2 = reservedSignedState.getAndReserve("reason")) {
                assertThrows(IllegalStateException.class, reservedSignedState2::get);
                assertNull(reservedSignedState2.getNullable());

                // reason is ignored for null state
                assertEquals("", reservedSignedState2.getReason());
                assertTrue(reservedSignedState2.isNull());
                assertFalse(reservedSignedState2.isNotNull());

                assertNotEquals(reservedSignedState.getReservationId(), reservedSignedState2.getReservationId());
            }
        }
    }

    @Test
    @DisplayName("Non-Null State Test")
    void NonNullStateTest() {
        final SignedState signedState = new RandomSignedStateGenerator().build();
        assertEquals(0, signedState.getReservationCount());

        try (final ReservedSignedState reservedSignedState =
                ReservedSignedState.createAndReserve(signedState, "reason")) {

            assertSame(signedState, reservedSignedState.get());
            assertSame(signedState, reservedSignedState.getNullable());
            assertEquals("reason", reservedSignedState.getReason());
            assertFalse(reservedSignedState.isNull());
            assertTrue(reservedSignedState.isNotNull());
            assertEquals(1, signedState.getReservationCount());

            try (final ReservedSignedState reservedSignedState2 = reservedSignedState.getAndReserve("reason2")) {
                assertSame(signedState, reservedSignedState2.get());
                assertSame(signedState, reservedSignedState2.getNullable());
                assertEquals("reason2", reservedSignedState2.getReason());
                assertFalse(reservedSignedState2.isNull());
                assertTrue(reservedSignedState2.isNotNull());
                assertEquals(2, signedState.getReservationCount());

                assertNotEquals(reservedSignedState.getReservationId(), reservedSignedState2.getReservationId());
            }
            assertEquals(1, signedState.getReservationCount());
        }
        assertEquals(-1, signedState.getReservationCount());
    }

    @Test
    @DisplayName("Null Bad Lifecycle Test")
    void nullBadLifecycleTest() {
        final ReservedSignedState reservedSignedState = createNullReservation();
        reservedSignedState.close();

        assertThrows(ReferenceCountException.class, reservedSignedState::get);
        assertThrows(ReferenceCountException.class, () -> reservedSignedState.getAndReserve("reason"));
        assertThrows(ReferenceCountException.class, reservedSignedState::getNullable);
        assertThrows(ReferenceCountException.class, reservedSignedState::isNull);
        assertThrows(ReferenceCountException.class, reservedSignedState::isNotNull);
        assertThrows(ReferenceCountException.class, reservedSignedState::close);
    }

    @Test
    @DisplayName("Non-Null Bad Lifecycle Test")
    void nonNullBadLifecycleTest() {
        final ReservedSignedState reservedSignedState =
                ReservedSignedState.createAndReserve(new RandomSignedStateGenerator().build(), "reason");
        reservedSignedState.close();

        assertThrows(ReferenceCountException.class, reservedSignedState::get);
        assertThrows(ReferenceCountException.class, () -> reservedSignedState.getAndReserve("reason"));
        assertThrows(ReferenceCountException.class, reservedSignedState::getNullable);
        assertThrows(ReferenceCountException.class, reservedSignedState::isNull);
        assertThrows(ReferenceCountException.class, reservedSignedState::isNotNull);
        assertThrows(ReferenceCountException.class, reservedSignedState::close);
    }

    @Test
    @DisplayName("Try-reserve Paradigm Test")
    void tryReserveTest() {
        final SignedState signedState = new RandomSignedStateGenerator().build();
        assertEquals(0, signedState.getReservationCount());

        final ReservedSignedState reservedSignedState = ReservedSignedState.createAndReserve(signedState, "reason");
        try {
            assertEquals(1, signedState.getReservationCount());

            try (final ReservedSignedState reservedSignedState2 =
                    reservedSignedState.tryGetAndReserve("successful try")) {
                assertNotNull(reservedSignedState2);
                assertNotSame(reservedSignedState, reservedSignedState2);
                assertSame(signedState, reservedSignedState2.get());
                assertSame(signedState, reservedSignedState2.getNullable());
                assertEquals("successful try", reservedSignedState2.getReason());
                assertFalse(reservedSignedState2.isNull());
                assertTrue(reservedSignedState2.isNotNull());
                assertEquals(2, signedState.getReservationCount());

                assertNotEquals(reservedSignedState.getReservationId(), reservedSignedState2.getReservationId());
            }
            assertEquals(1, signedState.getReservationCount());
        } finally {
            reservedSignedState.close();
        }
        assertEquals(-1, signedState.getReservationCount());
        try (final ReservedSignedState reservedSignedState2 = reservedSignedState.tryGetAndReserve("failed try")) {
            assertNull(reservedSignedState2);
        }
        assertEquals(-1, signedState.getReservationCount());
    }
}
