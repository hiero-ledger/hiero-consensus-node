// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb;

import static com.swirlds.virtualmap.datasource.VirtualDataSource.INVALID_PATH;

import java.util.Objects;

/**
 * A simple immutable POJO for keeping the min and max valid keys. These values are set atomically and
 * must be read atomically in other parts of the code, which this class facilitates.
 */
public final class KeyRange {
    /**
     * Keys are usually paths in our use cases, so we use INVALID_PATH = -1 here as nominal to indicate an invalid key.
     */
    public static final long INVALID_KEY = INVALID_PATH;
    /** A constant for the invalid key range */
    public static final KeyRange INVALID_KEY_RANGE = new KeyRange(INVALID_KEY, INVALID_KEY);
    /** The minimum valid key in the range. Must be less than or equal to the {@code maxValidKey}. */
    private final long minValidKey;
    /** The maximum valid key in the range. Must be greater than or equal to the {@code minValidKey}. */
    private final long maxValidKey;

    /**
     * Create a new {@link KeyRange}.
     *
     * @param minValidKey
     * 		The minimum valid key in the range. Must be less than or equal to the {@code maxValidKey}.
     * @param maxValidKey
     * 		The maximum valid key in the range. Must be greater than or equal to the {@code minValidKey}.
     */
    public KeyRange(final long minValidKey, final long maxValidKey) {
        if (maxValidKey < minValidKey) {
            throw new IllegalArgumentException(
                    "maxValidKey of " + maxValidKey + " must be less than minValidKey of " + minValidKey);
        }

        this.minValidKey = minValidKey;
        this.maxValidKey = maxValidKey;
    }

    /**
     * Get the min valid key.
     *
     * @return
     * 		The minimum key in the range.
     */
    public long getMinValidKey() {
        return minValidKey;
    }

    /**
     * Get the max valid key.
     *
     * @return
     * 		The maximum key in the range.
     */
    public long getMaxValidKey() {
        return maxValidKey;
    }

    /**
     * Gets whether the given {@code key} is within the range defined by this {@link KeyRange}.
     *
     * @return
     * 		True if the {@code key} is greater than or equal to the {@link #getMinValidKey()}} and
     * 	    less than or equal to the {@link #getMaxValidKey()}.
     */
    public boolean withinRange(long key) {
        return key >= minValidKey && key <= maxValidKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final KeyRange keyRange = (KeyRange) o;
        return minValidKey == keyRange.minValidKey && maxValidKey == keyRange.maxValidKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Objects.hash(minValidKey, maxValidKey);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "(" + minValidKey + "," + maxValidKey + ")";
    }
}
