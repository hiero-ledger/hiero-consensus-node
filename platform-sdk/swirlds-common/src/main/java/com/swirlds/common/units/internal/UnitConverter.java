// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.units.internal;

import com.swirlds.common.units.Unit;

/**
 * Boilerplate code for converting between units of the same type.
 *
 * @param <T> the type of the unit
 */
public class UnitConverter<T extends Unit<T>> {

    /**
     * Describes a unit with respect to the previous unit in the unit sequence.
     *
     * @param type   the type identifier for this unit
     * @param factor the number which the previous unit must be multiplied by in order to reach this unit
     * @param <T>    the unit family type for this unit
     */
    public record UnitConversion<T>(T type, long factor) {}

    /**
     * An in-order sequence of units.
     */
    private final T[] types;

    /**
     * Factors for converting between units.
     */
    private final long[] factors;

    /**
     * Create a new unit converter.
     *
     * @param units an in-order array of units
     */
    @SuppressWarnings("unchecked")
    public UnitConverter(final T... units) {
        factors = new long[units.length * units.length];
        types = (T[]) new Unit[units.length];

        for (final T from : units) {
            types[from.ordinal()] = from;
            for (final T to : units) {

                int index = getConversionFactorIndex(from, to);
                factors[index] = 1;

                if (from.ordinal() > to.ordinal()) {
                    for (int i = from.ordinal(); i > to.ordinal(); i--) {
                        factors[index] *= units[i].getConversionFactor();
                    }
                } else {
                    for (int i = to.ordinal(); i > from.ordinal(); i--) {
                        factors[index] *= units[i].getConversionFactor();
                    }
                }
            }
        }
    }

    /**
     * Get the index in {@link #factors} for a particular conversion pair.
     *
     * @param to   the starting unit
     * @param from the resulting unit
     * @return the factor that should be multiplied or divided for the conversion
     */
    private int getConversionFactorIndex(final Unit<T> to, final Unit<T> from) {
        return to.ordinal() * types.length + from.ordinal();
    }

    /**
     * Simplify a quantity to the best unit.
     *
     * @param quantity the quantity
     * @param unit     the original unit
     * @return the new unit
     */
    public Unit.SimplifiedQuantity<T> simplify(final double quantity, final T unit) {
        double currentQuantity = quantity;
        int currentIndex = unit.ordinal();

        while (currentIndex > 0 && Math.abs(currentQuantity) < 1.0) {
            currentIndex--;
            currentQuantity = convertTo(quantity, unit, types[currentIndex]);
        }
        while (currentIndex < types.length - 1
                && Math.abs(currentQuantity) >= types[currentIndex + 1].getConversionFactor()) {
            currentIndex++;
            currentQuantity = convertTo(quantity, unit, types[currentIndex]);
        }

        return new Unit.SimplifiedQuantity<>(currentQuantity, types[currentIndex]);
    }

    /**
     * Convert from one unit to another.
     *
     * @param quantity the original quantity
     * @param from     the original unit
     * @param to       the new unit
     * @return the new quantity
     */
    public double convertTo(final double quantity, final T from, final T to) {
        if (from == to) {
            return quantity;
        }

        final long factor = factors[getConversionFactorIndex(from, to)];

        if (from.ordinal() > to.ordinal()) {
            return quantity * factor;
        } else {
            return quantity / factor;
        }
    }

    /**
     * Convert from one unit to another.
     *
     * @param quantity the original quantity
     * @param from     the original unit
     * @param to       the new unit
     * @return the new quantity
     */
    public double convertTo(final long quantity, final T from, final T to) {
        if (from == to) {
            return quantity;
        }

        final long factor = factors[getConversionFactorIndex(from, to)];

        if (from.ordinal() > to.ordinal()) {
            return (double) (quantity * factor);
        } else {
            return ((double) quantity) / factor;
        }
    }
}
