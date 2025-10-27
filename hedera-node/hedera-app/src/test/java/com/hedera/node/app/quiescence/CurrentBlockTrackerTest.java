// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.quiescence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CurrentBlockTrackerTest {

    @Mock
    private QuiescenceBlockTracker tracker1;

    @Mock
    private QuiescenceBlockTracker tracker2;

    @Mock
    private QuiescenceBlockTracker tracker3;

    private CurrentBlockTracker currentBlockTracker;

    @BeforeEach
    void setUp() {
        currentBlockTracker = new CurrentBlockTracker();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {
        @Test
        @DisplayName("Constructor initializes successfully")
        void constructorInitializesSuccessfully() {
            final var newTracker = new CurrentBlockTracker();
            assertNotNull(newTracker);
        }

        @Test
        @DisplayName("trackerOrThrow throws when no tracker is set")
        void trackerOrThrowThrowsWhenNoTrackerSet() {
            final var newTracker = new CurrentBlockTracker();
            assertThrows(NullPointerException.class, newTracker::trackerOrThrow);
        }
    }

    @Nested
    @DisplayName("setTracker Tests")
    class SetTrackerTests {
        @Test
        @DisplayName("setTracker sets the tracker successfully")
        void setTrackerSetsTrackerSuccessfully() {
            currentBlockTracker.setTracker(tracker1);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker1, result);
        }

        @Test
        @DisplayName("setTracker throws NullPointerException when tracker is null")
        void setTrackerThrowsWhenTrackerIsNull() {
            assertThrows(NullPointerException.class, () -> currentBlockTracker.setTracker(null));
        }

        @Test
        @DisplayName("setTracker replaces existing tracker")
        void setTrackerReplacesExistingTracker() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.setTracker(tracker2);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker2, result);
        }

        @Test
        @DisplayName("setTracker does not call finishedHandlingTransactions on previous tracker")
        void setTrackerDoesNotCallFinishedHandlingTransactions() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.setTracker(tracker2);
            verify(tracker1, never()).finishedHandlingTransactions();
        }
    }

    @Nested
    @DisplayName("switchTracker Tests")
    class SwitchTrackerTests {
        @Test
        @DisplayName("switchTracker throws NullPointerException when tracker is null")
        void switchTrackerThrowsWhenTrackerIsNull() {
            assertThrows(NullPointerException.class, () -> currentBlockTracker.switchTracker(null));
        }

        @Test
        @DisplayName("switchTracker returns false when no previous tracker exists")
        void switchTrackerReturnsFalseWhenNoPreviousTracker() {
            final var result = currentBlockTracker.switchTracker(tracker1);
            assertFalse(result);
        }

        @Test
        @DisplayName("switchTracker sets the new tracker when no previous tracker exists")
        void switchTrackerSetsNewTrackerWhenNoPreviousTracker() {
            currentBlockTracker.switchTracker(tracker1);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker1, result);
        }

        @Test
        @DisplayName("switchTracker does not call finishedHandlingTransactions when no previous tracker")
        void switchTrackerDoesNotCallFinishedWhenNoPreviousTracker() {
            currentBlockTracker.switchTracker(tracker1);
            verify(tracker1, never()).finishedHandlingTransactions();
        }

        @Test
        @DisplayName("switchTracker returns true when previous tracker exists")
        void switchTrackerReturnsTrueWhenPreviousTrackerExists() {
            currentBlockTracker.setTracker(tracker1);
            final var result = currentBlockTracker.switchTracker(tracker2);
            assertTrue(result);
        }

        @Test
        @DisplayName("switchTracker calls finishedHandlingTransactions on previous tracker")
        void switchTrackerCallsFinishedHandlingTransactionsOnPreviousTracker() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.switchTracker(tracker2);
            verify(tracker1, times(1)).finishedHandlingTransactions();
        }

        @Test
        @DisplayName("switchTracker sets the new tracker when previous tracker exists")
        void switchTrackerSetsNewTrackerWhenPreviousTrackerExists() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.switchTracker(tracker2);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker2, result);
        }

        @Test
        @DisplayName("switchTracker does not call finishedHandlingTransactions on new tracker")
        void switchTrackerDoesNotCallFinishedOnNewTracker() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.switchTracker(tracker2);
            verify(tracker2, never()).finishedHandlingTransactions();
        }

        @Test
        @DisplayName("switchTracker multiple times calls finishedHandlingTransactions each time")
        void switchTrackerMultipleTimesCallsFinishedEachTime() {
            currentBlockTracker.switchTracker(tracker1);
            verify(tracker1, never()).finishedHandlingTransactions();

            currentBlockTracker.switchTracker(tracker2);
            verify(tracker1, times(1)).finishedHandlingTransactions();
            verify(tracker2, never()).finishedHandlingTransactions();

            currentBlockTracker.switchTracker(tracker3);
            verify(tracker1, times(1)).finishedHandlingTransactions();
            verify(tracker2, times(1)).finishedHandlingTransactions();
            verify(tracker3, never()).finishedHandlingTransactions();
        }

        @Test
        @DisplayName("switchTracker returns correct boolean for multiple switches")
        void switchTrackerReturnsCorrectBooleanForMultipleSwitches() {
            final var result1 = currentBlockTracker.switchTracker(tracker1);
            assertFalse(result1, "First switch should return false");

            final var result2 = currentBlockTracker.switchTracker(tracker2);
            assertTrue(result2, "Second switch should return true");

            final var result3 = currentBlockTracker.switchTracker(tracker3);
            assertTrue(result3, "Third switch should return true");
        }
    }

    @Nested
    @DisplayName("trackerOrThrow Tests")
    class TrackerOrThrowTests {
        @Test
        @DisplayName("trackerOrThrow returns the current tracker")
        void trackerOrThrowReturnsCurrentTracker() {
            currentBlockTracker.setTracker(tracker1);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker1, result);
        }

        @Test
        @DisplayName("trackerOrThrow throws NullPointerException when no tracker is set")
        void trackerOrThrowThrowsWhenNoTrackerSet() {
            assertThrows(NullPointerException.class, () -> currentBlockTracker.trackerOrThrow());
        }

        @Test
        @DisplayName("trackerOrThrow returns updated tracker after setTracker")
        void trackerOrThrowReturnsUpdatedTrackerAfterSetTracker() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.setTracker(tracker2);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker2, result);
        }

        @Test
        @DisplayName("trackerOrThrow returns updated tracker after switchTracker")
        void trackerOrThrowReturnsUpdatedTrackerAfterSwitchTracker() {
            currentBlockTracker.setTracker(tracker1);
            currentBlockTracker.switchTracker(tracker2);
            final var result = currentBlockTracker.trackerOrThrow();
            assertSame(tracker2, result);
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {
        @Test
        @DisplayName("Mixing setTracker and switchTracker works correctly")
        void mixingSetTrackerAndSwitchTrackerWorksCorrectly() {
            // Set initial tracker
            currentBlockTracker.setTracker(tracker1);
            verify(tracker1, never()).finishedHandlingTransactions();

            // Switch to tracker2 - should finalize tracker1
            final var result1 = currentBlockTracker.switchTracker(tracker2);
            assertTrue(result1);
            verify(tracker1, times(1)).finishedHandlingTransactions();
            assertSame(tracker2, currentBlockTracker.trackerOrThrow());

            // Set tracker3 directly - should NOT finalize tracker2
            currentBlockTracker.setTracker(tracker3);
            verify(tracker2, never()).finishedHandlingTransactions();
            assertSame(tracker3, currentBlockTracker.trackerOrThrow());
        }

        @Test
        @DisplayName("switchTracker after setTracker finalizes the set tracker")
        void switchTrackerAfterSetTrackerFinalizesSetTracker() {
            currentBlockTracker.setTracker(tracker1);
            final var result = currentBlockTracker.switchTracker(tracker2);
            assertTrue(result);
            verify(tracker1, times(1)).finishedHandlingTransactions();
        }
    }
}
