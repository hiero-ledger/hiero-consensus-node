// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generate random object based on some pre-defined weighted distribution or probability
 *
 * Reference https://gamedev.stackexchange.com/questions/162976/how-do-i-create-a-weighted-collection-and-then-pick-a
 * -random-element-from-it
 *
 */
public class WeightedRandomBag<T> {

    private class Entry {
        double accumulatedWeight;
        T object;
    }

    private final List<Entry> entries = new ArrayList<>();
    private double accumulatedWeight;
    private final Random rand = new Random();

    public void addEntry(T object, double weight) {
        accumulatedWeight += weight;
        Entry e = new Entry();
        e.object = object;
        e.accumulatedWeight = accumulatedWeight;
        entries.add(e);
    }

    public T getRandom() {
        double r = rand.nextDouble() * accumulatedWeight;

        for (Entry entry : entries) {
            if (entry.accumulatedWeight >= r) {
                return entry.object;
            }
        }
        return null; // should only happen when there are no entries
    }
}
