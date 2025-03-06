// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.set;

/**
 * Describes a hotspot in a {@link HotspotHashSet}.
 */
public class Hotspot {

    /**
     * The weight that determines the frequency that an object from this hotspot is chosen.
     */
    private final double weight;

    /**
     * The desired size of this hotspot.
     */
    private final int size;

    /**
     * Create a new description of a hotspot.
     *
     * @param weight
     * 		the weight of this hotspot. A weight of 2 will cause this hotspot to be chosen twice as
     * 		often as a hotspot with a weight of 1.
     * @param size
     * 		the number of elements in this hotspot
     */
    public Hotspot(final double weight, final int size) {
        this.weight = weight;
        this.size = size;
    }

    /**
     * Get the weight of this hotspot.
     */
    public double getWeight() {
        return weight;
    }

    /**
     * Get the maximum size that this hotspot would like to be.
     */
    public int getHotspotSize() {
        return size;
    }
}
