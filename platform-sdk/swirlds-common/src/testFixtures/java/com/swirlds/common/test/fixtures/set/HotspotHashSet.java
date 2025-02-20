// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.set;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;

/**
 * Similar to a {@link RandomAccessHashSet} but with hotspots (i.e. elements that are chosen more oftan than others).
 *
 * @param <T>
 * 		the type of the object held by the set
 */
public class HotspotHashSet<T> implements RandomAccessSet<T> {

    private final List<Hotspot> hotspots;
    private final List<RandomAccessHashSet<T>> sets;
    private final double totalWeight;
    private int totalCount;

    private static final int DEFAULT_SET_INDEX = 0;

    /**
     * Create a new random access set that has a number of hotspots.
     *
     * @param defaultWeight
     * 		the weight that determines the frequency that elements that are not in a hotspot are returned
     * 		If the set contains 1,000 elements and has a single hotspot with 100 elements then 900 elements
     * 		will be in the default set. If the default weight is 1.0 and a hotspot of 2.0, then elements
     * 		from the hotspot will be returned twice as often as elements in the default set.
     * @param hotspots
     * 		an array of zero or more hotspot configurations
     */
    public HotspotHashSet(final double defaultWeight, final Hotspot... hotspots) {
        final int hotspotCount = (hotspots == null ? 0 : hotspots.length) + 1;

        this.hotspots = new ArrayList<>(hotspotCount);
        sets = new ArrayList<>(hotspotCount);

        // Create default set
        this.hotspots.add(new Hotspot(defaultWeight, Integer.MAX_VALUE));
        sets.add(new RandomAccessHashSet<>());

        double weightSum = defaultWeight;

        if (hotspots != null) {
            for (final Hotspot hotspot : hotspots) {
                this.hotspots.add(hotspot);
                weightSum += hotspot.getWeight();
                sets.add(new RandomAccessHashSet<>());
            }
        }

        totalWeight = weightSum;
    }

    /**
     * Choose a random set of entries based on hotspot weight.
     *
     * @return the index of the chosen set
     */
    private int chooseSetIndexByWeight(final Random random) {
        double choice = random.nextDouble() * totalWeight;

        for (int index = 0; index < sets.size(); index++) {
            choice -= hotspots.get(index).getWeight();
            if (choice < 0) {
                return index;
            }
        }

        // If rounding error causes us to not choose a set
        // (exceptionally unlikely, if not impossible) then just return default.
        return DEFAULT_SET_INDEX;
    }

    /**
     * Choose a random set of entries where the weight is equal to the size of the set.
     *
     * @return the index of the chosen set
     */
    private int chooseSetIndexBySize(final Random random) {
        double choice = random.nextDouble() * totalCount;

        for (int index = 0; index < sets.size(); index++) {
            choice -= sets.get(index).size();
            if (choice < 0) {
                return index;
            }
        }

        // If rounding error causes us to not choose a set
        // (exceptionally unlikely, if not impossible) then just return default.
        return DEFAULT_SET_INDEX;
    }

    /**
     * Get a random element, taking hotspots into account.
     *
     * @param random
     * 		a source of randomness
     * @return an element chosen randomly
     * @throws NoSuchElementException
     * 		if the set is empty
     */
    public T getWeighted(final Random random) {
        if (totalCount == 0) {
            throw new NoSuchElementException("set is empty, can not get element");
        }

        while (true) {
            final int setIndex = chooseSetIndexByWeight(random);
            final RandomAccessHashSet<T> set = sets.get(setIndex);

            if (setIndex == DEFAULT_SET_INDEX) {
                if (set.size() > 0) {
                    return set.get(random);
                }
            } else {
                final Hotspot hotspot = hotspots.get(setIndex);
                if (hotspot.getHotspotSize() > set.size()) {
                    // Attempt to pull new element into this set
                    final RandomAccessHashSet<T> defaultSet = sets.get(DEFAULT_SET_INDEX);
                    if (defaultSet.size() > 0) {
                        final T element = defaultSet.get(random);
                        defaultSet.remove(element);
                        set.add(element);
                        return element;
                    }
                } else {
                    return set.get(random);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T get(final Random random) {
        if (totalCount == 0) {
            throw new NoSuchElementException("set is empty, can not get element");
        }

        final int setIndex = chooseSetIndexBySize(random);
        final RandomAccessHashSet<T> set = sets.get(setIndex);

        return set.get(random);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T get(final int index) {
        if (index >= totalCount || index < 0) {
            throw new IndexOutOfBoundsException("requested index " + index + " is invalid");
        }

        int count = 0;
        for (final RandomAccessHashSet<T> set : sets) {
            if (count + set.size() > index) {
                return set.get(index - count);
            }
            count += set.size();
        }

        throw new IllegalStateException("unable to find element at index " + index);
    }

    /**
     * Get a set containing all elements in a given hotspot.
     *
     * @param index
     * 		the index of the hotspot
     * @return a set containing the hotspot elements
     */
    public RandomAccessHashSet<T> getHotspotSet(final int index) {
        return sets.get(index);
    }

    /**
     * Get the total number of hotspots.
     */
    public int getHotspotCount() {
        return sets.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int size() {
        return totalCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isEmpty() {
        return totalCount == 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean contains(final Object o) {
        for (final Set<T> set : sets) {
            if (set.contains(o)) {
                return true;
            }
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterator<T> iterator() {
        return new HotspotSetIterator<>(sets);
    }

    /**
     * Add all elements in a set to an array at the given position.
     */
    private void addSetToArray(final Object[] array, final int startIndex, final RandomAccessHashSet<T> set) {
        for (int index = 0; index < set.size(); index++) {
            array[index + startIndex] = set.get(index);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] toArray() {
        final Object[] array = new Object[totalCount];

        int index = 0;
        for (final RandomAccessHashSet<T> set : sets) {
            addSetToArray(array, index, set);
            index += set.size();
        }

        return array;
    }

    /**
     * Unsupported.
     *
     * @throws UnsupportedOperationException
     * 		if this method is called
     */
    @Override
    public <T1> T1[] toArray(final T1[] a) {
        throw new UnsupportedOperationException();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(final T t) {
        final boolean added = sets.get(DEFAULT_SET_INDEX).add(t);
        if (added) {
            totalCount++;
        }
        return added;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(final Object o) {
        for (final Set<T> set : sets) {
            if (set.remove(o)) {
                totalCount--;
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsAll(final Collection<?> c) {
        for (final Object o : c) {
            if (!contains(o)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        for (final Set<T> set : sets) {
            set.clear();
        }
        totalCount = 0;
    }
}
