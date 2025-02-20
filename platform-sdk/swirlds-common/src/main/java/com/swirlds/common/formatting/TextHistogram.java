// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_RED;
import static com.swirlds.common.formatting.TextEffect.BRIGHT_YELLOW;
import static com.swirlds.common.formatting.TextEffect.GRAY;

import com.swirlds.common.units.DataUnit;
import com.swirlds.common.units.Unit;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * This class is capable of drawing histograms using text.
 *
 * @param <T>
 * 		the type of the data being plotted
 */
public class TextHistogram<T> {

    private static final int DEFAULT_HISTOGRAM_WIDTH = 32;
    private static final String HISTOGRAM_CHARACTER = "*";

    private boolean colorEnabled = true;
    private boolean showValues = true;
    private int width = DEFAULT_HISTOGRAM_WIDTH;
    private Function<T, Object> timestampExtractor;
    private final Function<T, Long> valueExtractor;
    private Unit<?> valueUnit;

    private final List<T> data;

    /**
     * Create a new text histogram.
     *
     * @param data
     * 		the data to plot
     */
    public TextHistogram(@NonNull final List<T> data, @NonNull final Function<T, Long> valueExtractor) {
        Objects.requireNonNull(data, "data must not be null");
        Objects.requireNonNull(valueExtractor, "valueExtractor must not be null");
        this.data = data;
        this.valueExtractor = valueExtractor;
    }

    /**
     * Set if color should be enabled. Has no effect if color is disabled globally.
     *
     * @param colorEnabled
     * 		if color should be enabled
     * @return this object
     */
    public TextHistogram<T> setColorEnabled(final boolean colorEnabled) {
        this.colorEnabled = colorEnabled;
        return this;
    }

    /**
     * Set if the values should be shown.
     *
     * @param showValues
     * 		if the values should be shown
     * @return this object
     */
    public TextHistogram<T> setShowValues(final boolean showValues) {
        this.showValues = showValues;
        return this;
    }

    /**
     * Set the unit for the value. Ignored if values are not shown.
     *
     * @param valueUnit
     * 		the unit for the value
     * @return this object
     */
    public TextHistogram<T> setValueUnit(final Unit<?> valueUnit) {
        this.valueUnit = valueUnit;
        return this;
    }

    /**
     * Set the maximum width of the histogram bars, in characters.
     *
     * @param width
     * 		the width of the histogram
     * @return this object
     */
    public TextHistogram<T> setWidth(final int width) {
        this.width = width;
        return this;
    }

    /**
     * Set the function to use to extract the timestamp from the data. If unset then no timestamp
     * is displayed.
     *
     * @param timestampExtractor
     * 		the function to use to extract the timestamp
     * @return this object
     */
    public TextHistogram<T> setTimestampExtractor(final Function<T, Object> timestampExtractor) {
        this.timestampExtractor = timestampExtractor;
        return this;
    }

    /**
     * Render the histogram to a string.
     *
     * @return the histogram in string form
     */
    public String render() {
        final StringBuilder sb = new StringBuilder();
        render(sb);
        return sb.toString();
    }

    /**
     * Render the histogram to a string builder.
     *
     * @param sb
     * 		the string builder to write to
     */
    public void render(final StringBuilder sb) {
        long max = 0;
        for (final T datum : data) {
            max = Math.max(max, valueExtractor.apply(datum));
        }

        final TextTable table = new TextTable()
                .setBordersEnabled(false)
                .setColumnHorizontalAlignment(1, HorizontalAlignment.ALIGNED_RIGHT);

        final UnitFormatter valueFormatter;
        if (valueUnit != null) {
            valueFormatter =
                    new UnitFormatter(DataUnit.UNIT_BYTES).setAbbreviate(true).setDecimalPlaces(1);
        } else {
            valueFormatter = null;
        }

        for (final T datum : data) {
            final List<String> row = new ArrayList<>();

            if (timestampExtractor != null) {
                final String timestamp = timestampExtractor.apply(datum).toString();
                if (colorEnabled) {
                    row.add(GRAY.apply(timestamp));
                } else {
                    row.add(timestamp);
                }
            }

            if (showValues) {
                final String value;
                if (valueFormatter != null) {
                    valueFormatter.setQuantity(valueExtractor.apply(datum));
                    value = valueFormatter.render();
                } else {
                    value = commaSeparatedNumber(valueExtractor.apply(datum));
                }

                if (colorEnabled) {
                    row.add(BRIGHT_RED.apply(value));
                } else {
                    row.add(value);
                }
            }

            final long value = valueExtractor.apply(datum);
            final int barLength = max == 0 ? 0 : (int) (((double) value) / max * width);
            final String bar = HISTOGRAM_CHARACTER.repeat(barLength);

            if (colorEnabled) {
                row.add(BRIGHT_YELLOW.apply(bar));
            } else {
                row.add(bar);
            }

            table.addRow(row.toArray());
        }

        table.render(sb);
    }
}
