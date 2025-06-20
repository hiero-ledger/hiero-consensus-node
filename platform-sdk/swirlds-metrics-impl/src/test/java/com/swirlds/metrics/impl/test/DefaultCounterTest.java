// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.impl.test;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.snapshot.Snapshot.SnapshotEntry;
import com.swirlds.metrics.impl.DefaultCounter;
import com.swirlds.metrics.impl.DefaultIntegerGauge;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefaultCounterTest {

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";

    @Test
    @DisplayName("Constructor should store values")
    void testConstructor() {
        final Counter.Config config =
                new Counter.Config(CATEGORY, NAME).withDescription(DESCRIPTION).withUnit(UNIT);
        final Counter counter = new DefaultCounter(config);

        assertEquals(CATEGORY, counter.getCategory(), "The category was not set correctly in the constructor");
        assertEquals(NAME, counter.getName(), "The name was not set correctly in the constructor");
        assertEquals(DESCRIPTION, counter.getDescription(), "The description was not set correctly in the constructor");
        assertEquals(UNIT, counter.getUnit(), "The unit was not set correctly in the constructor");
        assertEquals("%d", counter.getFormat(), "The format was not set correctly in constructor");
        assertEquals(0L, counter.get(), "The value was not initialized correctly");
        assertEquals(0L, counter.get(VALUE), "The value was not initialized correctly");
        assertThat(counter.getValueTypes()).containsExactly(VALUE);
    }

    @Test
    @DisplayName("Counter should add non-negative values")
    void testAddingValues() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final Counter counter = new DefaultCounter(config);

        // when
        counter.add(3L);

        // then
        assertEquals(3L, counter.get(), "Value should be 3");
        assertEquals(3L, counter.get(VALUE), "Value should be 3");

        // when
        counter.add(5L);

        // then
        assertEquals(8L, counter.get(), "Value should be 8");
        assertEquals(8L, counter.get(VALUE), "Value should be 8");
    }

    @Test
    @DisplayName("Counter should not allow to add negative value")
    void testAddingNegativeValueShouldFail() {
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final Counter counter = new DefaultCounter(config);
        assertThrows(
                IllegalArgumentException.class,
                () -> counter.add(-1L),
                "Calling add() with negative value should throw IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> counter.add(0),
                "Calling add() with negative value should throw IAE");
    }

    @Test
    @DisplayName("Counter should increment by 1")
    void testIncrement() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final Counter counter = new DefaultCounter(config);

        // when
        counter.increment();

        // then
        assertEquals(1L, counter.get(), "Value should be 1");
        assertEquals(1L, counter.get(VALUE), "Value should be 1");

        // when
        counter.increment();

        // then
        assertEquals(2L, counter.get(), "Value should be 2");
        assertEquals(2L, counter.get(VALUE), "Value should be 2");
    }

    @Test
    void testSnapshot() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final DefaultCounter counter = new DefaultCounter(config);
        counter.add(2L);

        // when
        final List<SnapshotEntry> snapshot = counter.takeSnapshot();

        // then
        assertEquals(2L, counter.get(), "Value should be 2");
        assertEquals(2L, counter.get(VALUE), "Value should be 2");
        assertThat(snapshot).containsExactly(new SnapshotEntry(VALUE, 2L));
    }

    @Test
    void testInvalidGets() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final Counter counter = new DefaultCounter(config);

        // then
        assertThrows(
                NullPointerException.class, () -> counter.get(null), "Calling get() with null should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> counter.get(Metric.ValueType.MIN),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> counter.get(Metric.ValueType.MAX),
                "Calling get() with an unsupported MetricType should throw an IAE");
        assertThrows(
                IllegalArgumentException.class,
                () -> counter.get(Metric.ValueType.STD_DEV),
                "Calling get() with an unsupported MetricType should throw an IAE");
    }

    @Test
    void testReset() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final Counter counter = new DefaultCounter(config);

        // then
        assertThatCode(counter::reset).doesNotThrowAnyException();
    }

    @Test
    void testEquals() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY, NAME);
        final Counter counter1 = new DefaultCounter(config);
        final Counter counter2 = new DefaultCounter(config);
        counter2.add(42L);

        // then
        assertThat(counter1)
                .isEqualTo(counter2)
                .hasSameHashCodeAs(counter2)
                .isNotEqualTo(new DefaultCounter(new Counter.Config("Other", NAME)))
                .isNotEqualTo(new DefaultCounter(new Counter.Config(CATEGORY, "Other")))
                .isNotEqualTo(new DefaultIntegerGauge(new IntegerGauge.Config(CATEGORY, NAME)));
    }

    @Test
    void testToString() {
        // given
        final Counter.Config config =
                new Counter.Config(CATEGORY, NAME).withDescription(DESCRIPTION).withUnit(UNIT);
        final Counter counter = new DefaultCounter(config);
        counter.add(42L);

        // then
        assertThat(counter.toString())
                .contains(CATEGORY, NAME, DESCRIPTION, UNIT, Metric.DataType.INT.toString(), "42");
    }
}
