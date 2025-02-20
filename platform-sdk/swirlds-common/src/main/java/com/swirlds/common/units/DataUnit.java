// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.units;

import static com.swirlds.base.units.UnitConstants.BYTES_TO_BITS;

import com.swirlds.common.units.internal.UnitConverter;

/**
 * Units for measurements of data quantity.
 */
public enum DataUnit implements Unit<DataUnit> {
    UNIT_BITS(1, "bit", "b"),
    UNIT_BYTES(BYTES_TO_BITS, "byte", "B"),
    UNIT_KILOBYTES(1024, "kilobyte", "KB"),
    UNIT_MEGABYTES(1024, "megabyte", "MB"),
    UNIT_GIGABYTES(1024, "gigabyte", "GB"),
    UNIT_TERABYTES(1024, "terabyte", "TB"),
    UNIT_PETABYTES(1024, "petabyte", "PB");

    private static final UnitConverter<DataUnit> converter = new UnitConverter<>(DataUnit.values());

    private final int conversionFactor;
    private final String name;
    private final String abbreviation;

    DataUnit(final int conversionFactor, final String name, final String abbreviation) {
        this.conversionFactor = conversionFactor;
        this.name = name;
        this.abbreviation = abbreviation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SimplifiedQuantity<DataUnit> simplify(final double quantity) {
        return converter.simplify(quantity, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double convertTo(final double quantity, final DataUnit to) {
        return converter.convertTo(quantity, this, to);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double convertTo(final long quantity, final DataUnit to) {
        return converter.convertTo(quantity, this, to);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getConversionFactor() {
        return conversionFactor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPluralName() {
        return name + "s";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAbbreviation() {
        return abbreviation;
    }
}
