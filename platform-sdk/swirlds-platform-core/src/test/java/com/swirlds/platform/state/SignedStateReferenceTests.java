// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.signed.SignedStateReference;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("SignedStateReference Tests")
class SignedStateReferenceTests {

    /**
     * Build a signed state.
     */
    public static SignedState buildSignedState() {
        return new RandomSignedStateGenerator().build();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Null Initial Value Test")
    void nullInitialValueTest(final boolean defaultValue) {
        final SignedStateReference reference;
        if (defaultValue) {
            reference = new SignedStateReference();
        } else {
            reference = new SignedStateReference(null, "test");
        }

        assertTrue(reference.isNull(), "should point to a null value");
        assertEquals(-1, reference.getRound(), "round should be -1 if state is null");

        final ReservedSignedState wrapper1 = reference.getAndReserve("test");
        assertNotNull(wrapper1, "wrapper should never be null");
        assertNull(wrapper1.getNullable(), "state should be null");
        wrapper1.close();

        final ReservedSignedState wrapper2 = reference.getAndReserve("test");
        assertNotNull(wrapper2, "wrapper should never be null");
        assertNull(wrapper2.getNullable(), "state should be null");
        wrapper2.close();

        final ReservedSignedState wrapper3 = reference.getAndReserve("test");
        assertNotNull(wrapper3, "wrapper should never be null");
        assertNull(wrapper3.getNullable(), "state should be null");
        wrapper3.close();

        // This should not break anything
        reference.set(null, "test");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Initial Value Constructor Test")
    void initialValueTest(final boolean defaultValue) {

        final SignedState state = spy(buildSignedState());
        doReturn(1234L).when(state).getRound();

        final SignedStateReference reference;
        if (defaultValue) {
            reference = new SignedStateReference();
            reference.set(state, "test");
        } else {
            reference = new SignedStateReference(state, "test");
        }

        assertFalse(reference.isNull(), "should not be null");
        assertEquals(1234, reference.getRound(), "invalid round");
        assertEquals(1, state.getReservationCount(), "invalid reference count");

        final ReservedSignedState wrapper1 = reference.getAndReserve("test");
        assertNotNull(wrapper1, "wrapper should never be null");
        assertSame(state, wrapper1.get(), "incorrect state");
        assertEquals(2, state.getReservationCount(), "incorrect reference count");
        wrapper1.close();
        assertEquals(1, state.getReservationCount(), "incorrect reference count");

        reference.set(null, "test");

        assertEquals(-1, state.getReservationCount(), "incorrect reference count");
    }

    @Test
    @DisplayName("Replacement Test")
    void replacementTest() {
        final SignedState state1 = buildSignedState();
        final SignedState state2 = buildSignedState();
        final SignedState state3 = buildSignedState();

        final SignedStateReference reference = new SignedStateReference(state1, "test");
        assertEquals(1, state1.getReservationCount(), "incorrect reference count");
        assertEquals(0, state2.getReservationCount(), "incorrect reference count");
        assertEquals(0, state3.getReservationCount(), "incorrect reference count");

        // replace value with itself
        reference.set(state1, "test");
        assertEquals(1, state1.getReservationCount(), "incorrect reference count");
        assertEquals(0, state2.getReservationCount(), "incorrect reference count");
        assertEquals(0, state3.getReservationCount(), "incorrect reference count");

        // replace non-null value with non-null value
        reference.set(state2, "test");
        assertEquals(-1, state1.getReservationCount(), "incorrect reference count");
        assertEquals(1, state2.getReservationCount(), "incorrect reference count");
        assertEquals(0, state3.getReservationCount(), "incorrect reference count");

        // replace non-null value with null
        reference.set(null, "test");
        assertEquals(-1, state1.getReservationCount(), "incorrect reference count");
        assertEquals(-1, state2.getReservationCount(), "incorrect reference count");
        assertEquals(0, state3.getReservationCount(), "incorrect reference count");

        // replace null with null
        reference.set(null, "test");
        assertEquals(-1, state1.getReservationCount(), "incorrect reference count");
        assertEquals(-1, state2.getReservationCount(), "incorrect reference count");
        assertEquals(0, state3.getReservationCount(), "incorrect reference count");

        // replace null with non-null value
        reference.set(state3, "test");
        assertEquals(-1, state1.getReservationCount(), "incorrect reference count");
        assertEquals(-1, state2.getReservationCount(), "incorrect reference count");
        assertEquals(1, state3.getReservationCount(), "incorrect reference count");
    }
}
