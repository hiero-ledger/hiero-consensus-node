// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Configures a hotspot for sequence of PTT transactions. Each transaction type may handle
 * hotspots differently.
 */
public class HotspotConfiguration {

    private static final Random RANDOM = new SecureRandom();

    private int size;
    private double frequency;

    /**
     * Get the size of the configured hotspot.
     */
    public int getSize() {
        return size;
    }

    /**
     * Set the size of the configured hotspot.
     */
    public void setSize(final int size) {
        this.size = size;
    }

    /**
     * Get the frequency of the configured hotspot, a number between 0.0 and 1.0.
     */
    public double getFrequency() {
        return frequency;
    }

    /**
     * Set the frequency of the configured hotspot, a number between 0.0 and 1.0.
     */
    public void setFrequency(final double frequency) {
        this.frequency = frequency;
    }

    /**
     *  Determines whether the hotspot configuration should be used
     *  based on the specified frequency
     *
     * @return Whether the hotspot configuration should be used
     */
    public boolean shouldBeUsed() {
        final double choice = RANDOM.nextDouble();
        return choice < frequency;
    }
}
