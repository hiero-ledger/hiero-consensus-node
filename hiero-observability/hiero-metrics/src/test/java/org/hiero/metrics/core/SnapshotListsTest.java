// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotListsTest {

    private static final Comparator<MeasurementSnapshot> BY_LABEL_VALUES =
            (first, second) -> LabelValues.compare(first.getDynamicLabelValues(), second.getDynamicLabelValues());

    private static final Comparator<Item> BY_KEY = Comparator.comparing(Item::key);

    @Test
    void testSortKeepsLastOfMeasurementsWithEqualLabelValues() {
        MeasurementSnapshot removed = measurementSnapshot("a");
        MeasurementSnapshot other = measurementSnapshot("b");
        MeasurementSnapshot createdAgain = measurementSnapshot("a");
        // iteration over a concurrent map returns an entry removed and added again during the iteration twice
        List<MeasurementSnapshot> iterated = new ArrayList<>(List.of(removed, other, createdAgain));

        assertThat(SnapshotLists.sortKeepingLastOfEqual(iterated, BY_LABEL_VALUES))
                .containsExactly(createdAgain, other);
    }

    @Test
    void testEmptyListStaysEmpty() {
        assertThat(SnapshotLists.sortKeepingLastOfEqual(new ArrayList<>(), BY_KEY))
                .isEmpty();
    }

    @Test
    void testSortsWithoutEqualKeys() {
        List<Item> iterated = items(new Item("c", 1), new Item("a", 1), new Item("b", 1));

        assertThat(SnapshotLists.sortKeepingLastOfEqual(iterated, BY_KEY))
                .containsExactly(new Item("a", 1), new Item("b", 1), new Item("c", 1));
    }

    @Test
    void testKeepsLastOfThreeOrMoreEqualKeys() {
        List<Item> iterated = items(new Item("a", 1), new Item("a", 2), new Item("a", 3), new Item("a", 4));

        assertThat(SnapshotLists.sortKeepingLastOfEqual(iterated, BY_KEY)).containsExactly(new Item("a", 4));
    }

    @Test
    void testKeepsLastOfEachGroupOfEqualKeys() {
        List<Item> iterated = items(
                new Item("b", 1),
                new Item("a", 1),
                new Item("b", 2),
                new Item("c", 1),
                new Item("a", 2),
                new Item("b", 3));

        assertThat(SnapshotLists.sortKeepingLastOfEqual(iterated, BY_KEY))
                .containsExactly(new Item("a", 2), new Item("b", 3), new Item("c", 1));
    }

    @Test
    void testResultIsUnmodifiable() {
        List<Item> sorted = SnapshotLists.sortKeepingLastOfEqual(items(new Item("a", 1)), BY_KEY);

        assertThatThrownBy(() -> sorted.add(new Item("b", 1))).isInstanceOf(UnsupportedOperationException.class);
    }

    // equal keys with different ids tell which of the equal items is kept
    private record Item(String key, int id) {}

    private static List<Item> items(Item... items) {
        return new ArrayList<>(List.of(items));
    }

    private static MeasurementSnapshot measurementSnapshot(String labelValue) {
        return new LongMeasurementSnapshot(new LabelValues("label", labelValue), () -> 0L);
    }
}
