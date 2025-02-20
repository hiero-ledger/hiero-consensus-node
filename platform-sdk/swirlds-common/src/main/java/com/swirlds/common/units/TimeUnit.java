// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.units;

import static com.swirlds.base.units.UnitConstants.DAYS_TO_HOURS;
import static com.swirlds.base.units.UnitConstants.HOURS_TO_MINUTES;
import static com.swirlds.base.units.UnitConstants.MICROSECONDS_TO_NANOSECONDS;
import static com.swirlds.base.units.UnitConstants.MILLISECONDS_TO_MICROSECONDS;
import static com.swirlds.base.units.UnitConstants.MILLISECOND_UNIT;
import static com.swirlds.base.units.UnitConstants.MINUTES_TO_SECONDS;
import static com.swirlds.base.units.UnitConstants.NANOSECOND_UNIT;
import static com.swirlds.base.units.UnitConstants.SECONDS_TO_MILLISECONDS;
import static com.swirlds.base.units.UnitConstants.SECOND_UNIT;

import com.swirlds.common.units.internal.UnitConverter;

/**
 * Units for measurements of time.
 */
public enum TimeUnit implements Unit<TimeUnit> {
    UNIT_NANOSECONDS(1, "nanosecond", NANOSECOND_UNIT),
    UNIT_MICROSECONDS(MICROSECONDS_TO_NANOSECONDS, "microsecond", "us"),
    UNIT_MILLISECONDS(MILLISECONDS_TO_MICROSECONDS, "millisecond", MILLISECOND_UNIT),
    UNIT_SECONDS(SECONDS_TO_MILLISECONDS, "second", SECOND_UNIT),
    UNIT_MINUTES(MINUTES_TO_SECONDS, "minute", "m"),
    UNIT_HOURS(HOURS_TO_MINUTES, "hour", "h"),
    UNIT_DAYS(DAYS_TO_HOURS, "day", "d");

    private static final UnitConverter<TimeUnit> converter = new UnitConverter<>(TimeUnit.values());

    private final int conversionFactor;
    private final String name;
    private final String abbreviation;

    TimeUnit(final int conversionFactor, final String name, final String abbreviation) {
        this.conversionFactor = conversionFactor;
        this.name = name;
        this.abbreviation = abbreviation;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SimplifiedQuantity<TimeUnit> simplify(final double quantity) {
        return converter.simplify(quantity, this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double convertTo(final double quantity, final TimeUnit to) {
        return converter.convertTo(quantity, this, to);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public double convertTo(final long quantity, final TimeUnit to) {
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
