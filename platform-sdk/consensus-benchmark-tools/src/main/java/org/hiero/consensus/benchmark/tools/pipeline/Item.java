// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A ready-made input for a pipeline benchmark without a recording: an immutable item with a time, which enters the
 * pipeline itself ({@code input(Item.class, item -> item, entry)}). Each item is a separate object, so that the timer
 * recognizes it at the output.
 *
 * @param time the time the item is due
 */
public record Item(@NonNull Instant time) implements TimedInput {

    /**
     * Creates an item.
     *
     * @param time the time the item is due
     */
    public Item {
        requireNonNull(time);
    }

    /**
     * Returns this item, which no pass can change.
     *
     * @return this item
     */
    @Override
    @NonNull
    public Item pristine() {
        return this;
    }

    /**
     * Creates items at a fixed rate, the first at {@link Instant#EPOCH}.
     *
     * @param count the number of items
     * @param perSecond the items per second
     * @return the items, in the order of their times
     * @throws IllegalArgumentException if the count is negative or the rate not positive
     */
    @NonNull
    public static List<Item> evenlySpaced(final int count, final double perSecond) {
        if (count < 0 || !(perSecond > 0) || Double.isInfinite(perSecond)) {
            throw new IllegalArgumentException(
                    "Expected a non-negative count and a positive rate, but got " + count + " and " + perSecond);
        }
        final List<Item> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            items.add(new Item(Instant.EPOCH.plusNanos(Math.round(i * 1e9 / perSecond))));
        }
        return Collections.unmodifiableList(items);
    }
}
