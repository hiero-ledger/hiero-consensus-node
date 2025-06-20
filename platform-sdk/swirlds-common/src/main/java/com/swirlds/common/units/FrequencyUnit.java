// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.units;

import com.swirlds.common.units.internal.UnitConverter;

/**
 * Units for describing frequency.
 */
public enum FrequencyUnit implements Unit<FrequencyUnit> {
    UNIT_HERTZ(1, "hertz", "Hz"),
    UNIT_KILOHERTZ(1000, "kilohertz", "kHz"),
    UNIT_MEGAHERTZ(1000, "megahertz", "MHz"),
    UNIT_GIGAHERTZ(1000, "gigahertz", "GHz"),
    UNIT_TERAHERTZ(1000, "terahertz", "THz");

    private static final UnitConverter<FrequencyUnit> converter = new UnitConverter<>(FrequencyUnit.values());

    private final int conversionFactor;
    private final String name;
    private final String abbreviation;

    FrequencyUnit(final int conversionFactor, final String name, final String abbreviation) {
        this.conversionFactor = conversionFactor;
        this.name = name;
        this.abbreviation = abbreviation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SimplifiedQuantity<FrequencyUnit> simplify(final double quantity) {
        return converter.simplify(quantity, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double convertTo(final double quantity, final FrequencyUnit to) {
        return converter.convertTo(quantity, this, to);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double convertTo(final long quantity, final FrequencyUnit to) {
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
        return name;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAbbreviation() {
        return abbreviation;
    }
}
