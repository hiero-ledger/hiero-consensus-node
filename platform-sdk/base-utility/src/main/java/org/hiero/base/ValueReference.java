// SPDX-License-Identifier: Apache-2.0
package org.hiero.base;

/**
 * A utility class for passing a reference to an object.
 * Cheaper than using an atomic reference when atomicity is not needed.
 *
 * @param <V>
 * 		the type of the value being passed
 */
public class ValueReference<V> {

    private V value;

    /**
     * Create a new ValueReference with an initial value of null.
     */
    public ValueReference() {}

    /**
     * Create a new ValueReference with an initial value.
     *
     * @param value
     * 		the initial value
     */
    public ValueReference(final V value) {
        this.value = value;
    }

    /**
     * Get the value.
     */
    public V getValue() {
        return value;
    }

    /**
     * Set the value.
     */
    public void setValue(final V value) {
        this.value = value;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "[" + (value == null ? null : value) + "]";
    }
}
