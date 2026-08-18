// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CursoredLog Test")
class CursoredLogTest {

    /** A test item that carries its own sequence number, extracted by {@link Item#sequenceNumber()}. */
    private record Item(String name, long sequenceNumber) {
        @Override
        @NonNull
        public String toString() {
            return name;
        }
    }

    /** Drains everything a cursor can currently see. */
    private static <T> List<T> drain(final CursoredLog.Cursor<T> cursor) {
        final List<T> items = new ArrayList<>();
        while (cursor.hasNext()) {
            items.add(cursor.next());
        }
        return items;
    }

    /** Creates an empty log using {@link Item#sequenceNumber()} as the sequence number extractor. */
    private static CursoredLog<Item> newLog() {
        return new CursoredLog<>(1, 8, 8, Item::sequenceNumber);
    }

    /**
     * Creates a log holding items with the sequence numbers given, where item {@code i} is named {@code "item" + i}.
     */
    private static CursoredLog<Item> logWithSequenceNumbers(final long... sequenceNumbers) {
        final CursoredLog<Item> log = newLog();
        for (int i = 0; i < sequenceNumbers.length; i++) {
            log.add(new Item("item" + i, sequenceNumbers[i]));
        }
        return log;
    }

    // ==================== The sequence number extractor ====================

    @Test
    @DisplayName("the sequence number is taken from the extractor function")
    void sequenceNumberComesFromExtractor() {
        // an extractor that deliberately disagrees with the item's own field, to prove it is the one consulted
        final CursoredLog<Item> log = new CursoredLog<>(1, 8, 8, item -> item.sequenceNumber() * 10);

        log.add(new Item("scaled", 3));

        assertThat(log.getItemsWithSequenceNumber(30)).extracting(Item::name).containsExactly("scaled");
        assertThat(log.getItemsWithSequenceNumber(3)).isEmpty();
    }

    @Test
    @DisplayName("removal uses the extracted sequence number")
    void removalUsesExtractedSequenceNumber() {
        final CursoredLog<Item> log = new CursoredLog<>(1, 8, 8, item -> item.sequenceNumber() * 10);

        log.add(new Item("a", 1));
        log.add(new Item("b", 2));

        assertThat(log.removeSequenceNumber(10)).isEqualTo(1);
        assertThat(drain(log.newCursor())).extracting(Item::name).containsExactly("b");
    }

    // ==================== Ordering and appending ====================

    @Test
    @DisplayName("items are iterated in the order they were added")
    void iteratesInInsertionOrder() {
        final CursoredLog<Item> log = logWithSequenceNumbers(3, 1, 2, 1, 3);

        assertThat(drain(log.newCursor()))
                .extracting(Item::name)
                .containsExactly("item0", "item1", "item2", "item3", "item4");
        assertThat(log.size()).isEqualTo(5);
    }

    @Test
    @DisplayName("add returns consecutive positions")
    void addReturnsConsecutivePositions() {
        final CursoredLog<Item> log = newLog();

        assertThat(log.add(new Item("a", 1))).isZero();
        assertThat(log.add(new Item("b", 1))).isEqualTo(1);
        assertThat(log.add(new Item("c", 2))).isEqualTo(2);
        assertThat(log.nextPosition()).isEqualTo(3);
    }

    @Test
    @DisplayName("equal items are each given their own position")
    void equalItemsEachGetTheirOwnPosition() {
        final CursoredLog<Item> log = newLog();
        final Item item = new Item("duplicate", 1);

        assertThat(log.add(item)).isZero();
        assertThat(log.add(item)).isEqualTo(1);

        assertThat(log.size()).isEqualTo(2);
        assertThat(log.getItemsWithSequenceNumber(1)).containsExactly(item, item);
    }

    @Test
    @DisplayName("growing the buffer preserves order and positions")
    void growthPreservesOrderAndPositions() {
        final CursoredLog<Item> log = newLog();
        final List<String> expected = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            assertThat(log.add(new Item("item" + i, i / 10L + 1))).isEqualTo(i);
            expected.add("item" + i);
        }

        assertThat(log.size()).isEqualTo(1000);
        assertThat(drain(log.newCursor())).extracting(Item::name).isEqualTo(expected);
        assertThat(log.get(500)).extracting(Item::name).isEqualTo("item500");
    }

    // ==================== Random access ====================

    @Test
    @DisplayName("get reads an arbitrary position and returns null outside the log")
    void getReadsArbitraryPosition() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 2);

        assertThat(log.get(0)).extracting(Item::name).isEqualTo("item0");
        assertThat(log.get(2)).extracting(Item::name).isEqualTo("item2");
        assertThat(log.get(-1)).isNull();
        assertThat(log.get(3)).isNull();
    }

    @Test
    @DisplayName("get returns null for a removed position")
    void getReturnsNullForRemovedPosition() {
        final CursoredLog<Item> log = logWithSequenceNumbers(2, 1, 2);

        log.removeSequenceNumber(1);

        assertThat(log.get(1)).isNull();
        assertThat(log.get(2)).extracting(Item::name).isEqualTo("item2");
    }

    // ==================== Removal by sequence number ====================

    @Test
    @DisplayName("removeSequenceNumber removes interspersed items and iteration skips the gaps")
    void removalRemovesInterspersedItems() {
        final CursoredLog<Item> log = logWithSequenceNumbers(2, 1, 2, 3, 1, 2);

        assertThat(log.removeSequenceNumber(1)).isEqualTo(2);

        assertThat(drain(log.newCursor())).extracting(Item::name).containsExactly("item0", "item2", "item3", "item5");
        assertThat(log.size()).isEqualTo(4);
    }

    @Test
    @DisplayName("removal leaves tombstones that keep the span wider than the size")
    void removalLeavesTombstones() {
        final CursoredLog<Item> log = logWithSequenceNumbers(2, 1, 2, 3, 1, 2);

        log.removeSequenceNumber(1);

        assertThat(log.size()).isEqualTo(4);
        assertThat(log.span()).isEqualTo(6);
    }

    @Test
    @DisplayName("removing the leading sequence number reclaims tombstones from the front")
    void removingLeadingSequenceNumberReclaimsFront() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 2, 2);

        log.removeSequenceNumber(1);

        assertThat(log.firstPosition()).isEqualTo(2);
        assertThat(log.span()).isEqualTo(2);
        assertThat(log.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("removing a sequence number with no items still advances the window")
    void removingSequenceNumberWithNoItems() {
        final CursoredLog<Item> log = logWithSequenceNumbers(3, 4);

        assertThat(log.removeSequenceNumber(2)).isZero();

        assertThat(log.size()).isEqualTo(2);
        assertThat(log.getFirstSequenceNumberInWindow()).isEqualTo(3);
    }

    @Test
    @DisplayName("removal also drops items with a lower sequence number that were skipped over")
    void removalDropsSkippedLowerSequenceNumbers() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3, 4);

        // sequence numbers 1 and 2 were never removed individually, but can never be removed later either
        assertThat(log.removeSequenceNumber(3)).isEqualTo(3);

        assertThat(drain(log.newCursor())).extracting(Item::name).containsExactly("item3");
    }

    @Test
    @DisplayName("removal is empty when nothing carries the sequence number or anything lower")
    void removalOfEmptyLowerRange() {
        final CursoredLog<Item> log = logWithSequenceNumbers(5, 5);

        assertThat(log.removeSequenceNumber(4)).isZero();
        assertThat(log.removeSequenceNumber(5)).isEqualTo(2);
        assertThat(log.isEmpty()).isTrue();
        assertThat(log.span()).isZero();
    }

    // ==================== The strictly increasing contract ====================

    @Test
    @DisplayName("removal advances the window past the sequence number removed")
    void removalAdvancesWindow() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3);

        assertThat(log.getFirstSequenceNumberInWindow()).isEqualTo(1);
        log.removeSequenceNumber(2);
        assertThat(log.getFirstSequenceNumberInWindow()).isEqualTo(3);
    }

    @Test
    @DisplayName("removing the same sequence number twice throws")
    void repeatedRemovalThrows() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3);

        log.removeSequenceNumber(2);

        assertThatThrownBy(() -> log.removeSequenceNumber(2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");
    }

    @Test
    @DisplayName("removing a lower sequence number than one already removed throws")
    void decreasingRemovalThrows() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3);

        log.removeSequenceNumber(2);

        assertThatThrownBy(() -> log.removeSequenceNumber(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");
    }

    @Test
    @DisplayName("adding an item with a removed sequence number throws")
    void addWithRemovedSequenceNumberThrows() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3);

        log.removeSequenceNumber(2);

        assertThatThrownBy(() -> log.add(new Item("stale", 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already been removed");
        assertThatThrownBy(() -> log.add(new Item("stale", 1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("items may still be added with a sequence number at or above the window")
    void addAfterRemovalIsAllowed() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3);

        log.removeSequenceNumber(2);
        final Long position = log.add(new Item("later", 3));

        assertThat(position).isEqualTo(3);
        assertThat(log.get(position)).extracting(Item::name).isEqualTo("later");
        assertThat(log.getItemsWithSequenceNumber(3)).extracting(Item::name).containsExactly("item2", "later");
    }

    // ==================== Sequence number lookup ====================

    @Test
    @DisplayName("getItemsWithSequenceNumber returns items in insertion order")
    void itemsWithSequenceNumberAreOrdered() {
        final CursoredLog<Item> log = logWithSequenceNumbers(2, 1, 2, 3, 2);

        assertThat(log.getItemsWithSequenceNumber(2)).extracting(Item::name).containsExactly("item0", "item2", "item4");
        assertThat(log.getItemsWithSequenceNumber(1)).extracting(Item::name).containsExactly("item1");
        assertThat(log.getItemsWithSequenceNumber(9)).isEmpty();
    }

    // ==================== Cursors ====================

    @Test
    @DisplayName("multiple cursors advance independently")
    void cursorsAreIndependent() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 1, 1);

        final CursoredLog.Cursor<Item> first = log.newCursor();
        final CursoredLog.Cursor<Item> second = log.newCursor();

        assertThat(first.next().name()).isEqualTo("item0");
        assertThat(first.next().name()).isEqualTo("item1");

        assertThat(second.next().name()).isEqualTo("item0");

        assertThat(first.position()).isEqualTo(2);
        assertThat(second.position()).isEqualTo(1);
    }

    @Test
    @DisplayName("a cursor can be seeked to an arbitrary position and iterate from there")
    void cursorSeeksToArbitraryPosition() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 1, 1, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        cursor.seek(3);

        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item3", "item4");

        cursor.seek(1);
        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item1", "item2", "item3", "item4");
    }

    @Test
    @DisplayName("seek clamps to the bounds of the log")
    void seekClampsToBounds() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        cursor.seek(-100);
        assertThat(cursor.position()).isZero();

        cursor.seek(100);
        assertThat(cursor.position()).isEqualTo(3);
        assertThat(cursor.hasNext()).isFalse();
    }

    @Test
    @DisplayName("seekToFirst and seekToEnd move to the ends of the log")
    void seekToFirstAndEnd() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        cursor.seekToEnd();
        assertThat(cursor.hasNext()).isFalse();

        cursor.seekToFirst();
        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item0", "item1", "item2");
    }

    @Test
    @DisplayName("a cursor follows items added after it was exhausted")
    void cursorFollowsTail() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item0", "item1");
        assertThat(cursor.hasNext()).isFalse();

        log.add(new Item("item2", 2));

        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item2");
    }

    @Test
    @DisplayName("newCursorAtEnd yields only items added afterwards")
    void cursorAtEndSkipsExistingItems() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursorAtEnd();

        log.add(new Item("item2", 2));

        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item2");
    }

    @Test
    @DisplayName("a cursor left behind by the front is moved forward to the oldest surviving item")
    void cursorBehindFrontClampsForward() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1, 1, 2, 2);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        log.removeSequenceNumber(1);

        assertThat(cursor.position()).isEqualTo(3);
        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item3", "item4");
    }

    @Test
    @DisplayName("a cursor skips items removed from the middle while keeping its place")
    void cursorSkipsRemovedItems() {
        final CursoredLog<Item> log = logWithSequenceNumbers(2, 1, 1, 2, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        assertThat(cursor.next().name()).isEqualTo("item0");
        log.removeSequenceNumber(1);

        assertThat(cursor.position()).isEqualTo(3);
        assertThat(drain(cursor)).extracting(Item::name).containsExactly("item3");
    }

    @Test
    @DisplayName("peek returns the next item without advancing")
    void peekDoesNotAdvance() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        assertThat(cursor.peek()).extracting(Item::name).isEqualTo("item0");
        assertThat(cursor.peek()).extracting(Item::name).isEqualTo("item0");
        assertThat(cursor.next().name()).isEqualTo("item0");
        assertThat(cursor.peek()).extracting(Item::name).isEqualTo("item1");
    }

    @Test
    @DisplayName("peek returns null and next throws when the cursor is exhausted")
    void exhaustedCursor() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1);
        final CursoredLog.Cursor<Item> cursor = log.newCursor();

        cursor.next();

        assertThat(cursor.peek()).isNull();
        assertThatThrownBy(cursor::next).isInstanceOf(NoSuchElementException.class);
    }

    // ==================== Clearing ====================

    @Test
    @DisplayName("clear empties the log without reusing positions")
    void clearEmptiesTheLog() {
        final CursoredLog<Item> log = logWithSequenceNumbers(1, 2, 3);

        log.clear();

        assertThat(log.isEmpty()).isTrue();
        assertThat(log.span()).isZero();
        assertThat(log.getFirstSequenceNumberInWindow()).isEqualTo(1);
        assertThat(log.add(new Item("after", 1))).isEqualTo(3);
        assertThat(drain(log.newCursor())).extracting(Item::name).containsExactly("after");
    }

    // ==================== Argument validation ====================

    @Test
    @DisplayName("a non-positive capacity is rejected")
    void nonPositiveCapacityRejected() {
        assertThatThrownBy(() -> new CursoredLog<Item>(1, 0, 8, Item::sequenceNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNumberCapacity");
        assertThatThrownBy(() -> new CursoredLog<Item>(1, 8, 0, Item::sequenceNumber))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("initialCapacity");
    }

    @Test
    @DisplayName("adding a null item is rejected")
    void nullItemRejected() {
        final CursoredLog<Item> log = newLog();

        assertThatThrownBy(() -> log.add(null)).isInstanceOf(NullPointerException.class);
    }
}
