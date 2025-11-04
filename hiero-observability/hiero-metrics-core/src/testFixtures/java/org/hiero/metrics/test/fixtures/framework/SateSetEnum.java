// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.test.fixtures.framework;

import java.util.Random;

/**
 * An enumeration representing a set of states for testing purposes.
 */
public enum SateSetEnum {
    STATE_ONE,
    STATE_TWO,
    STATE_THREE,
    STATE_FOUR,
    STATE_FIVE;

    private static final Random RANDOM = new Random();

    /**
     * @return a randomly selected enum value
     */
    public static SateSetEnum randomStateSet() {
        SateSetEnum[] values = SateSetEnum.values();
        return values[RANDOM.nextInt(values.length)];
    }
}
