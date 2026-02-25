// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.test.fixtures.set.Hotspot;
import com.swirlds.common.test.fixtures.set.HotspotHashSet;
import com.swirlds.common.test.fixtures.set.RandomAccessHashSet;
import com.swirlds.common.test.fixtures.set.RandomAccessSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("RandomAccessSet Test")
class RandomAccessSetTest {

    /**
     * Build a reference set and a RandomAccessSet.
     */
    private Pair<Set<Integer>, RandomAccessSet<Integer>> buildPair(Supplier<RandomAccessSet<Integer>> constructor) {
        return Pair.of(new HashSet<>(), constructor.get());
    }

    /**
     * Add to both sets in the pair.
     */
    private void add(Pair<Set<Integer>, RandomAccessSet<Integer>> pair, final int value) {
        pair.left().add(value);
        pair.right().add(value);
    }

    /**
     * Remove from both sets in the pair.
     */
    private void remove(Pair<Set<Integer>, RandomAccessSet<Integer>> pair, final int value) {
        pair.left().remove(value);
        pair.right().remove(value);
    }

    /**
     * Assert that the set and reference set contain the same elements.
     */
    private void validate(Pair<Set<Integer>, RandomAccessSet<Integer>> pair) {
        final Set<Integer> reference = pair.left();
        final RandomAccessSet<Integer> randomAccessSet = pair.right();

        assertEquals(reference.size(), randomAccessSet.size(), "sets should be the same size");
        assertEquals(reference, randomAccessSet, "sets should contain the same elements");

        final Set<Integer> derivedSet = new HashSet<>();
        for (int index = 0; index < randomAccessSet.size(); index++) {
            derivedSet.add(randomAccessSet.get(index));
        }

        assertEquals(reference, derivedSet, "values from random access should match");
    }

    /**
     * Do randomized inserts and removes on RandomAccessHashSet,
     * assert that set contains same elements as reference set.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Randomized Hash Set Test")
    void randomizedHashSetTest() {

        final Random random = new Random();

        final int iterations = 100_000;
        final int maxValue = 10_000;

        final Pair<Set<Integer>, RandomAccessSet<Integer>> pair = buildPair(RandomAccessHashSet::new);

        for (int i = 0; i < iterations; i++) {
            add(pair, random.nextInt(maxValue));
            remove(pair, random.nextInt(maxValue));

            if (i % 100 == 0) {
                validate(pair);
            }
        }

        validate(pair);
    }

    /**
     * Do randomized inserts and removes on HotspotHashSet, assert that set contains same elements as reference set.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Randomized Hotspot Set Test")
    void randomizedHotspotSetTest() {

        final Random random = new Random();

        final int iterations = 100_000;
        final int maxValue = 10_000;

        final Pair<Set<Integer>, RandomAccessSet<Integer>> pair = buildPair(() -> new HotspotHashSet<>(1.0));

        for (int i = 0; i < iterations; i++) {
            add(pair, random.nextInt(maxValue));
            remove(pair, random.nextInt(maxValue));

            if (i % 100 == 0) {
                validate(pair);
            }
        }

        validate(pair);
    }

    /**
     * Ensure that all elements appear with even probability in RandomAccessHashSet.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Hash Set Distribution Test")
    void hashSetDistributionTest() {

        final Random random = new Random();

        final int maximumValue = 100;
        final int iterations = 10_000_000;
        final double allowedDeviation = 0.05;

        final RandomAccessSet<Integer> set = new RandomAccessHashSet<>();
        final Map<Integer, Integer> frequencyMap = new HashMap<>();

        // populate the set
        for (int value = 0; value < maximumValue; value++) {
            set.add(value);
            frequencyMap.put(value, 0);
        }

        // get a bunch of values
        for (int i = 0; i < iterations; i++) {
            final int value = set.get(random);
            frequencyMap.put(value, frequencyMap.get(value) + 1);
        }

        final int averageCount = iterations / maximumValue;
        final int allowableMinimum = (int) (averageCount * (1 - allowedDeviation));
        final int allowableMaximum = (int) (averageCount * (1 + allowedDeviation));

        for (int value = 0; value < maximumValue; value++) {
            final int count = frequencyMap.get(value);
            assertTrue(
                    count >= allowableMinimum,
                    "count (" + count + ") expected to be greater than minimum (" + allowableMinimum + ")");
            assertTrue(
                    count <= allowableMaximum,
                    "count (" + count + ") expected to be less than maximum (" + allowableMaximum + ")");
        }
    }

    /**
     * Ensure that all elements appear with even probability when standard get method is called on HotspotHashSet
     * that has no configured hotspots.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Hotspot Simple Distribution Test")
    void hotspotSimpleDistributionTest() {

        final Random random = new Random();

        final int maximumValue = 100;
        final int iterations = 10_000_000;
        final double allowedDeviation = 0.05;

        final RandomAccessSet<Integer> set = new HotspotHashSet<>(1.0);
        final Map<Integer, Integer> frequencyMap = new HashMap<>();

        // populate the set
        for (int value = 0; value < maximumValue; value++) {
            set.add(value);
            frequencyMap.put(value, 0);
        }

        // get a bunch of values
        for (int i = 0; i < iterations; i++) {
            final int value = set.get(random);
            frequencyMap.put(value, frequencyMap.get(value) + 1);
        }

        final int averageCount = iterations / maximumValue;
        final int allowableMinimum = (int) (averageCount * (1 - allowedDeviation));
        final int allowableMaximum = (int) (averageCount * (1 + allowedDeviation));

        for (int value = 0; value < maximumValue; value++) {
            final int count = frequencyMap.get(value);
            assertTrue(
                    count >= allowableMinimum,
                    "count (" + count + ") expected to be greater than minimum (" + allowableMinimum + ")");
            assertTrue(
                    count <= allowableMaximum,
                    "count (" + count + ") expected to be less than maximum (" + allowableMaximum + ")");
        }
    }

    /**
     * Ensure that all elements appear with even probability when getWeighted method is called on
     * HotspotHashSet that has no configured hotspots.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Hotspot Simple Distribution Weighted Test")
    void hotspotSimpleDistributionWeightedTest() {

        final Random random = new Random();

        final int maximumValue = 100;
        final int iterations = 10_000_000;
        final double allowedDeviation = 0.05;

        final HotspotHashSet<Integer> set = new HotspotHashSet<>(1.0);
        final Map<Integer, Integer> frequencyMap = new HashMap<>();

        // populate the set
        for (int value = 0; value < maximumValue; value++) {
            set.add(value);
            frequencyMap.put(value, 0);
        }

        // get a bunch of values
        for (int i = 0; i < iterations; i++) {
            final int value = set.getWeighted(random);
            frequencyMap.put(value, frequencyMap.get(value) + 1);
        }

        final int averageCount = iterations / maximumValue;
        final int allowableMinimum = (int) (averageCount * (1 - allowedDeviation));
        final int allowableMaximum = (int) (averageCount * (1 + allowedDeviation));

        for (int value = 0; value < maximumValue; value++) {
            final int count = frequencyMap.get(value);
            assertTrue(
                    count >= allowableMinimum,
                    "count (" + count + ") expected to be greater than minimum (" + allowableMinimum + ")");
            assertTrue(
                    count <= allowableMaximum,
                    "count (" + count + ") expected to be less than maximum (" + allowableMaximum + ")");
        }
    }

    /**
     * Ensure that all elements appear with even probability when standard get method is called on
     * HotspotHashSet that has a single hotspot.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Hotspot One Hotspot Test")
    void hotspotOneHotspotTest() {

        final Random random = new Random();

        final int maximumValue = 100;
        final int iterations = 10_000_000;
        final double allowedDeviation = 0.05;

        final double defaultWeight = 1.0;
        final double hotspotWeight = 1.0;
        final int hotspotSize = 5;

        final HotspotHashSet<Integer> set =
                new HotspotHashSet<>(defaultWeight, new Hotspot(hotspotWeight, hotspotSize));
        final Map<Integer, Integer> frequencyMap = new HashMap<>();

        // populate the set
        for (int value = 0; value < maximumValue; value++) {
            set.add(value);
            frequencyMap.put(value, 0);
        }

        // get a bunch of values
        for (int i = 0; i < iterations; i++) {
            final int value = set.get(random);
            frequencyMap.put(value, frequencyMap.get(value) + 1);
        }

        final int averageCount = iterations / maximumValue;
        final int allowableMinimum = (int) (averageCount * (1 - allowedDeviation));
        final int allowableMaximum = (int) (averageCount * (1 + allowedDeviation));

        for (int value = 0; value < maximumValue; value++) {
            final int count = frequencyMap.get(value);
            assertTrue(
                    count >= allowableMinimum,
                    "count (" + count + ") expected to be greater than minimum (" + allowableMinimum + ")");
            assertTrue(
                    count <= allowableMaximum,
                    "count (" + count + ") expected to be less than maximum (" + allowableMaximum + ")");
        }
    }

    /**
     * Ensure that all elements appear at correct probability when getWeighted method is called on
     * HotspotHashSet that has a single hotspot.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Hotspot One Hotspot Test")
    void hotspotOneHotspotWeightedTest() {

        final Random random = new Random();

        final int maximumValue = 100;
        final int iterations = 10_000_000;
        final double allowedDeviation = 0.05;

        final double defaultWeight = 1.0;
        final double hotspotWeight = 1.0;
        final int hotspotSize = 5;

        final HotspotHashSet<Integer> set =
                new HotspotHashSet<>(defaultWeight, new Hotspot(hotspotWeight, hotspotSize));
        final Map<Integer, Integer> frequencyMap = new HashMap<>();

        // populate the set
        for (int value = 0; value < maximumValue; value++) {
            set.add(value);
            frequencyMap.put(value, 0);
        }

        // get a bunch of values
        for (int i = 0; i < iterations; i++) {
            final int value = set.getWeighted(random);
            frequencyMap.put(value, frequencyMap.get(value) + 1);
        }

        final int defaultAverageCount = iterations / (maximumValue - hotspotSize) / 2;
        final int defaultAllowableMinimum = (int) (defaultAverageCount * (1 - allowedDeviation));
        final int defaultAllowableMaximum = (int) (defaultAverageCount * (1 + allowedDeviation));

        final int hotspotAverageCount = iterations / (hotspotSize) / 2;
        final int hotspotAllowableMinimum = (int) (hotspotAverageCount * (1 - allowedDeviation));
        final int hotspotAllowableMaximum = (int) (hotspotAverageCount * (1 + allowedDeviation));

        for (int value = 0; value < maximumValue; value++) {
            final int count = frequencyMap.get(value);

            if (set.getHotspotSet(0).contains(value)) {
                // value is from default set
                assertTrue(
                        count >= defaultAllowableMinimum,
                        "count (" + count + ") expected to be greater than minimum (" + defaultAllowableMinimum + ")");
                assertTrue(
                        count <= defaultAllowableMaximum,
                        "count (" + count + ") expected to be less than maximum (" + defaultAllowableMaximum + ")");
            } else if (set.getHotspotSet(1).contains(value)) {
                assertTrue(
                        count >= hotspotAllowableMinimum,
                        "count (" + count + ") expected to be greater than minimum (" + hotspotAllowableMinimum + ")");
                assertTrue(
                        count <= hotspotAllowableMaximum,
                        "count (" + count + ") expected to be less than maximum (" + hotspotAllowableMaximum + ")");
            } else {
                fail("unable to find value " + value);
            }
        }
    }

    /**
     * Ensure that all elements appear at correct probability when getWeighted method is called on
     * HotspotHashSet that has 3 hotspots.
     */
    @Test
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Hotspot Three Hotspots Test")
    void hotspotThreeHotspotsWeightedTest() {

        final Random random = new Random();

        final int maximumValue = 100;
        final int iterations = 10_000_000;
        final double allowedDeviation = 0.05;

        final double defaultWeight = 2.0;
        final double hotspot1Weight = 1.0;
        final int hotspot1Size = 10;
        final double hotspot2Weight = 1.0;
        final int hotspot2Size = 5;
        final double hotspot3Weight = 0.5;
        final int hotspot3Size = 1;

        final int defaultSize = maximumValue - (hotspot1Size + hotspot2Size + hotspot3Size);
        final double totalWeight = defaultWeight + hotspot1Weight + hotspot2Weight + hotspot3Weight;

        final HotspotHashSet<Integer> set = new HotspotHashSet<>(
                defaultWeight,
                new Hotspot(hotspot1Weight, hotspot1Size),
                new Hotspot(hotspot2Weight, hotspot2Size),
                new Hotspot(hotspot3Weight, hotspot3Size));
        final Map<Integer, Integer> frequencyMap = new HashMap<>();

        // populate the set
        for (int value = 0; value < maximumValue; value++) {
            set.add(value);
            frequencyMap.put(value, 0);
        }

        // get a bunch of values
        for (int i = 0; i < iterations; i++) {
            final int value = set.getWeighted(random);
            frequencyMap.put(value, frequencyMap.get(value) + 1);
        }

        final int defaultAverageCount = (int) (iterations * defaultWeight / totalWeight / defaultSize);
        final int defaultAllowableMinimum = (int) (defaultAverageCount * (1 - allowedDeviation));
        final int defaultAllowableMaximum = (int) (defaultAverageCount * (1 + allowedDeviation));

        final int hotspot1AverageCount = (int) (iterations * hotspot1Weight / totalWeight / hotspot1Size);
        final int hotspot1AllowableMinimum = (int) (hotspot1AverageCount * (1 - allowedDeviation));
        final int hotspot1AllowableMaximum = (int) (hotspot1AverageCount * (1 + allowedDeviation));

        final int hotspot2AverageCount = (int) (iterations * hotspot2Weight / totalWeight / hotspot2Size);
        final int hotspot2AllowableMinimum = (int) (hotspot2AverageCount * (1 - allowedDeviation));
        final int hotspot2AllowableMaximum = (int) (hotspot2AverageCount * (1 + allowedDeviation));

        final int hotspot3AverageCount = (int) (iterations * hotspot3Weight / totalWeight / hotspot3Size);
        final int hotspot3AllowableMinimum = (int) (hotspot3AverageCount * (1 - allowedDeviation));
        final int hotspot3AllowableMaximum = (int) (hotspot3AverageCount * (1 + allowedDeviation));

        for (int value = 0; value < maximumValue; value++) {
            final int count = frequencyMap.get(value);

            if (set.getHotspotSet(0).contains(value)) {
                // value is from default set
                assertTrue(
                        count >= defaultAllowableMinimum,
                        "count (" + count + ") expected to be greater than minimum (" + defaultAllowableMinimum + ")");
                assertTrue(
                        count <= defaultAllowableMaximum,
                        "count (" + count + ") expected to be less than maximum (" + defaultAllowableMaximum + ")");
            } else if (set.getHotspotSet(1).contains(value)) {
                assertTrue(
                        count >= hotspot1AllowableMinimum,
                        "count (" + count + ") expected to be greater than minimum (" + hotspot1AllowableMinimum + ")");
                assertTrue(
                        count <= hotspot1AllowableMaximum,
                        "count (" + count + ") expected to be less than maximum (" + hotspot1AllowableMaximum + ")");
            } else if (set.getHotspotSet(2).contains(value)) {
                assertTrue(
                        count >= hotspot2AllowableMinimum,
                        "count (" + count + ") expected to be greater than minimum (" + hotspot2AllowableMinimum + ")");
                assertTrue(
                        count <= hotspot2AllowableMaximum,
                        "count (" + count + ") expected to be less than maximum (" + hotspot2AllowableMaximum + ")");
            } else if (set.getHotspotSet(3).contains(value)) {
                assertTrue(
                        count >= hotspot3AllowableMinimum,
                        "count (" + count + ") expected to be greater than minimum (" + hotspot3AllowableMinimum + ")");
                assertTrue(
                        count <= hotspot3AllowableMaximum,
                        "count (" + count + ") expected to be less than maximum (" + hotspot3AllowableMaximum + ")");
            } else {
                fail("unable to find value " + value);
            }
        }
    }
}
