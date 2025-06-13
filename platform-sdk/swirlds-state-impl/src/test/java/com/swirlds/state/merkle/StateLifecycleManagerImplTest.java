// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle;

import static org.junit.jupiter.api.Assertions.*;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import java.util.function.LongSupplier;
import org.hiero.base.exceptions.ReferenceCountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StateLifecycleManagerImplTest {

    private NoOpMetrics noOpMetrics;

    @Mock
    private Time mockTime;

    @Mock
    private MerkleCryptography mockCryptography;

    @Mock
    private LongSupplier mockRoundSupplier;

    @Mock
    private Configuration configuration;

    private StateLifecycleManagerImpl stateLifecycleManager;

    @BeforeEach
    void setUp() {
        noOpMetrics = new NoOpMetrics();
        stateLifecycleManager = new StateLifecycleManagerImpl(noOpMetrics);
    }

    private TestMerkleStateRoot createInitializedState() {
        TestMerkleStateRoot state = new TestMerkleStateRoot();
        state.init(mockTime, configuration, noOpMetrics, mockCryptography, mockRoundSupplier);
        return state;
    }

    @Test
    void testSetInitialState() {
        // Arrange
        TestMerkleStateRoot state = createInitializedState();

        // Act
        stateLifecycleManager.setInitialState(state);

        // Assert
        State mutableState = stateLifecycleManager.getMutableState();
        assertNotNull(mutableState);
        assertNotSame(state, mutableState);

        // Verify state is now immutable
        assertTrue(state.isImmutable());
    }

    @Test
    void testSetInitialStateWhenAlreadySet() {
        // Arrange
        TestMerkleStateRoot firstState = createInitializedState();
        stateLifecycleManager.setInitialState(firstState);

        // Create a second state
        TestMerkleStateRoot secondState = createInitializedState();

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> stateLifecycleManager.setInitialState(secondState));

        // Verify first state is still in place
        assertSame(stateLifecycleManager.getMutableState(), stateLifecycleManager.getMutableState());
    }

    @Test
    void testOverwriteExistingState() {
        // Arrange
        TestMerkleStateRoot firstState = createInitializedState();
        stateLifecycleManager.setInitialState(firstState);

        State firstMutableState = stateLifecycleManager.getMutableState();

        // Create a new state for the overwrite
        State newState = createInitializedState();

        // Act
        stateLifecycleManager.overwriteExistingState(newState);

        // Assert
        State newMutableState = stateLifecycleManager.getMutableState();
        assertNotNull(newMutableState);
        assertNotSame(newState, newMutableState);
        assertNotSame(firstMutableState, newMutableState);

        // Verify new state is immutable
        assertTrue(newState.isImmutable());
    }

    @Test
    void testOverwriteExistingStateWhenNoInitialState() {
        // Arrange
        State newState = createInitializedState();

        // Act
        stateLifecycleManager.overwriteExistingState(newState);

        // Assert
        State mutableState = stateLifecycleManager.getMutableState();
        assertNotNull(mutableState);
        assertNotSame(newState, mutableState);

        // Verify state is immutable
        assertTrue(newState.isImmutable());
    }

    @Test
    void testGetMutableStateWhenNoState() {
        // Act
        State result = stateLifecycleManager.getMutableState();

        // Assert
        assertNull(result);
    }

    @Test
    void testGetMutableState() {
        // Arrange
        State state = createInitializedState();
        stateLifecycleManager.setInitialState(state);

        // Act
        State result = stateLifecycleManager.getMutableState();

        // Assert
        assertNotNull(result);
        assertNotSame(state, result);
    }

    @Test
    void testCopyMutableState() {
        // Arrange
        State state = createInitializedState();
        stateLifecycleManager.setInitialState(state);

        State firstMutableState = stateLifecycleManager.getMutableState();

        // Act
        State immutableResult = stateLifecycleManager.copyMutableState();
        State newMutableState = stateLifecycleManager.getMutableState();

        // Assert
        assertNotNull(immutableResult);
        assertSame(firstMutableState, immutableResult);
        assertNotNull(newMutableState);
        assertNotSame(immutableResult, newMutableState);

        // Verify the returned state is immutable
        assertTrue(immutableResult.isImmutable());
    }

    @Test
    void testCopyMutableStateWhenNoState() {
        // Act & Assert
        assertThrows(NullPointerException.class, () -> stateLifecycleManager.copyMutableState());
    }

    @Test
    void testMultipleCopyOperations() {
        // Arrange
        TestMerkleStateRoot initialState = createInitializedState();
        stateLifecycleManager.setInitialState(initialState);

        // First copy operation
        State firstMutableState = stateLifecycleManager.getMutableState();
        State firstImmutable = stateLifecycleManager.copyMutableState();
        State secondMutableState = stateLifecycleManager.getMutableState();

        // Second copy operation
        State secondImmutable = stateLifecycleManager.copyMutableState();
        State thirdMutableState = stateLifecycleManager.getMutableState();

        // Assert
        assertSame(firstMutableState, firstImmutable);
        assertSame(secondMutableState, secondImmutable);
        assertNotSame(firstMutableState, secondMutableState);
        assertNotSame(secondMutableState, thirdMutableState);

        // Verify all previous states are immutable
        assertTrue(initialState.isImmutable());
        assertTrue(firstImmutable.isImmutable());
        assertTrue(secondImmutable.isImmutable());

        // Verify current mutable state is mutable
        assertFalse(thirdMutableState.isImmutable());
    }

    @Test
    void testSetNullInitialState() {
        // Act & Assert
        assertThrows(NullPointerException.class, () -> stateLifecycleManager.setInitialState(null));
    }

    @Test
    void testOverwriteNullState() {
        // Act & Assert
        assertThrows(NullPointerException.class, () -> stateLifecycleManager.overwriteExistingState(null));
    }

    @Test
    void testSetImmutableInitialState() {
        // Arrange
        TestMerkleStateRoot state = createInitializedState();
        state.setImmutable(true);

        // Act & Assert
        assertThrows(IllegalStateException.class, () -> stateLifecycleManager.setInitialState(state));
    }

    @Test
    void testSetDestroyedInitialState() {
        // Arrange
        TestMerkleStateRoot state = createInitializedState();
        state.release();

        // Act & Assert
        assertThrows(ReferenceCountException.class, () -> stateLifecycleManager.setInitialState(state));
    }

    private static class TestMerkleStateRoot extends MerkleStateRoot<TestMerkleStateRoot> {
        @Override
        protected TestMerkleStateRoot copyingConstructor() {
            return new TestMerkleStateRoot();
        }
    }
}
