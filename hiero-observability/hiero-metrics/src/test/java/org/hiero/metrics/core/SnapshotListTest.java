// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SnapshotListTest {

    private static final Comparator<MeasurementSnapshot> BY_LABEL_VALUES =
            (first, second) -> LabelValues.compare(first.getDynamicLabelValues(), second.getDynamicLabelValues());

    private static final Comparator<Item> BY_KEY = Comparator.comparing(Item::key);

    private final List<Item> source = new ArrayList<>();
    private final List<Item> updated = new ArrayList<>();
    private final AtomicInteger collections = new AtomicInteger();

    @Test
    void testFirstUpdateRebuildsAndLaterUpdatesOnlyAfterChanges() {
        SnapshotList<Item> list = listOfSource(updated::add);
        source.add(new Item("a", 1));

        assertThat(list).as("empty before the first update").isEmpty();
        assertThat(list.update()).as("first update").isTrue();
        assertThat(list.update()).as("nothing changed").isFalse();
        assertThat(collections).hasValue(1);
        assertThat(list).containsExactly(new Item("a", 1));

        source.add(new Item("b", 1));
        assertThat(list.update()).as("not marked").isFalse();
        assertThat(list).as("change not marked yet").containsExactly(new Item("a", 1));

        list.markChanged();
        assertThat(list.update()).as("marked").isTrue();
        assertThat(list.update()).as("nothing changed").isFalse();
        assertThat(collections).hasValue(2);
        assertThat(list).containsExactly(new Item("a", 1), new Item("b", 1));
    }

    @Test
    void testEveryUpdateUpdatesAllSnapshots() {
        SnapshotList<Item> list = listOfSource(updated::add);
        source.add(new Item("b", 1));
        source.add(new Item("a", 1));

        list.update();
        list.update();

        assertThat(updated)
                .as("updated in sorted order, also by the update that does not rebuild")
                .containsExactly(new Item("a", 1), new Item("b", 1), new Item("a", 1), new Item("b", 1));
    }

    @Test
    void testRebuildSortsAndKeepsLastOfEqual() {
        SnapshotList<Item> list = listOfSource(updated::add);
        source.addAll(List.of(new Item("b", 1), new Item("a", 1), new Item("b", 2)));

        list.update();

        assertThat(list).containsExactly(new Item("a", 1), new Item("b", 2));
    }

    @Test
    void testChangeDuringRebuildIsRebuiltByNextUpdate() {
        AtomicReference<SnapshotList<Item>> list = new AtomicReference<>();
        source.add(new Item("a", 1));
        list.set(new SnapshotList<>(
                BY_KEY,
                () -> {
                    List<Item> collected = new ArrayList<>(source);
                    if (collections.incrementAndGet() == 1) {
                        // the flag is cleared before collecting, so a change made meanwhile marks it again
                        source.add(new Item("b", 1));
                        list.get().markChanged();
                    }
                    return collected;
                },
                updated::add));

        assertThat(list.get().update()).isTrue();
        assertThat(list.get()).containsExactly(new Item("a", 1));

        assertThat(list.get().update()).as("marked during the rebuild").isTrue();
        assertThat(list.get()).containsExactly(new Item("a", 1), new Item("b", 1));
        assertThat(list.get().update()).as("nothing changed").isFalse();
    }

    @Test
    void testFailedRebuildKeepsIteratedListAndIsRetriedByNextUpdate() {
        AtomicBoolean failing = new AtomicBoolean();
        SnapshotList<Item> list = new SnapshotList<>(
                BY_KEY,
                () -> {
                    if (failing.get()) {
                        throw new IllegalStateException("collection failed");
                    }
                    return new ArrayList<>(source);
                },
                updated::add);
        source.add(new Item("a", 1));
        list.update();

        source.add(new Item("b", 1));
        list.markChanged();
        failing.set(true);
        assertThatThrownBy(list::update).hasMessage("collection failed");
        assertThat(list).as("iterated list is kept").containsExactly(new Item("a", 1));

        failing.set(false);
        assertThat(list.update()).as("still marked after the failed rebuild").isTrue();
        assertThat(list).containsExactly(new Item("a", 1), new Item("b", 1));
    }

    @Test
    void testRebuiltListIsPublishedAfterValuesAreUpdated() {
        AtomicReference<SnapshotList<Item>> list = new AtomicReference<>();
        List<Item> readDuringUpdate = new ArrayList<>();
        list.set(listOfSource(_ -> list.get().forEach(readDuringUpdate::add)));
        source.add(new Item("a", 1));
        list.get().update();

        source.add(new Item("b", 1));
        list.get().markChanged();
        readDuringUpdate.clear();
        list.get().update();

        assertThat(readDuringUpdate)
                .as("iterations during the update read the previous list")
                .containsExactly(new Item("a", 1), new Item("a", 1));
        assertThat(list.get()).containsExactly(new Item("a", 1), new Item("b", 1));
    }

    @Test
    void testRebuiltListIsPublishedWhenValueUpdateFails() {
        AtomicBoolean failing = new AtomicBoolean(true);
        SnapshotList<Item> list = listOfSource(item -> {
            if (failing.get()) {
                throw new IllegalStateException("update failed");
            }
            updated.add(item);
        });
        source.add(new Item("a", 1));

        assertThatThrownBy(list::update).hasMessage("update failed");
        assertThat(list)
                .as("published, because the source is not marked anymore")
                .containsExactly(new Item("a", 1));

        failing.set(false);
        assertThat(list.update()).as("not rebuilt again").isFalse();
        assertThat(updated).containsExactly(new Item("a", 1));
    }

    @Test
    void testStartedIterationIsNotAffectedByRebuild() {
        SnapshotList<Item> list = listOfSource(updated::add);
        source.add(new Item("a", 1));
        list.update();
        Iterator<Item> started = list.iterator();

        source.add(new Item("b", 1));
        list.markChanged();
        list.update();

        assertThat(started).toIterable().containsExactly(new Item("a", 1));
        assertThat(list).containsExactly(new Item("a", 1), new Item("b", 1));
    }

    @Test
    void testSortKeepsLastOfMeasurementsWithEqualLabelValues() {
        MeasurementSnapshot removed = measurementSnapshot("a");
        MeasurementSnapshot other = measurementSnapshot("b");
        MeasurementSnapshot createdAgain = measurementSnapshot("a");
        // iteration over a concurrent map returns an entry removed and added again during the iteration twice
        List<MeasurementSnapshot> iterated = new ArrayList<>(List.of(removed, other, createdAgain));

        assertThat(SnapshotList.sortKeepingLastOfEqual(iterated, BY_LABEL_VALUES))
                .containsExactly(createdAgain, other);
    }

    @Test
    void testEmptyListStaysEmpty() {
        assertThat(SnapshotList.sortKeepingLastOfEqual(new ArrayList<>(), BY_KEY))
                .isEmpty();
    }

    @Test
    void testSortsWithoutEqualKeys() {
        List<Item> iterated = items(new Item("c", 1), new Item("a", 1), new Item("b", 1));

        assertThat(SnapshotList.sortKeepingLastOfEqual(iterated, BY_KEY))
                .containsExactly(new Item("a", 1), new Item("b", 1), new Item("c", 1));
    }

    @Test
    void testKeepsLastOfThreeOrMoreEqualKeys() {
        List<Item> iterated = items(new Item("a", 1), new Item("a", 2), new Item("a", 3), new Item("a", 4));

        assertThat(SnapshotList.sortKeepingLastOfEqual(iterated, BY_KEY)).containsExactly(new Item("a", 4));
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

        assertThat(SnapshotList.sortKeepingLastOfEqual(iterated, BY_KEY))
                .containsExactly(new Item("a", 2), new Item("b", 3), new Item("c", 1));
    }

    @Test
    void testResultIsUnmodifiable() {
        List<Item> sorted = SnapshotList.sortKeepingLastOfEqual(items(new Item("a", 1)), BY_KEY);

        assertThatThrownBy(() -> sorted.add(new Item("b", 1))).isInstanceOf(UnsupportedOperationException.class);
    }

    // a snapshot list of the source items, counting its collections
    private SnapshotList<Item> listOfSource(Consumer<Item> updater) {
        Supplier<List<Item>> collector = () -> {
            collections.incrementAndGet();
            return new ArrayList<>(source);
        };
        return new SnapshotList<>(BY_KEY, collector, updater);
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
