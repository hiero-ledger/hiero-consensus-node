// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A formatted text table with a fluent API.
 *
 * <p>Columns can be named or headless, typed (int/float) or untyped (string),
 * and may carry a fixed value that is repeated in every row without consuming
 * a value from {@link #addRow}.
 */
public class StringTable {

    private enum ColumnType {
        STRING,
        INT,
        FLOAT
    }

    private static class ColumnDef {
        String header;
        ColumnType type = ColumnType.STRING;
        int width;
        int precision;
        String fixedValue;
    }

    private final List<ColumnDef> columns;
    private final List<Object[]> rows = new ArrayList<>();

    private StringTable(@NonNull final List<ColumnDef> columns) {
        this.columns = List.copyOf(columns);
    }

    /**
     * Adds a data row. Values are mapped in order to non-fixed columns;
     * fixed-value columns are filled automatically and must not be included.
     *
     * @param values the values for each non-fixed column
     */
    public void addRow(@NonNull final Object... values) {
        final Object[] fullRow = new Object[columns.size()];
        int valueIndex = 0;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).fixedValue != null) {
                fullRow[i] = columns.get(i).fixedValue;
            } else {
                fullRow[i] = values[valueIndex++];
            }
        }
        rows.add(fullRow);
    }

    @Override
    @NonNull
    public String toString() {
        final String[][] formatted = new String[rows.size()][columns.size()];
        final int[] widths = new int[columns.size()];

        for (int c = 0; c < columns.size(); c++) {
            final ColumnDef col = columns.get(c);
            widths[c] = col.header != null ? col.header.length() : 0;
            if (col.fixedValue != null) {
                widths[c] = Math.max(widths[c], col.fixedValue.length());
            }
            if (col.width > 0) {
                widths[c] = Math.max(widths[c], col.width);
            }
        }

        // Format all cells and widen columns as needed
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < columns.size(); c++) {
                formatted[r][c] = formatCell(columns.get(c), rows.get(r)[c]);
                widths[c] = Math.max(widths[c], formatted[r][c].length());
            }
        }

        final StringBuilder sb = new StringBuilder();

        // Header row
        for (int c = 0; c < columns.size(); c++) {
            if (c > 0) {
                sb.append(' ');
            }
            final String headerText = columns.get(c).header != null ? columns.get(c).header : "";
            sb.append(padRight(headerText, widths[c]));
        }
        sb.append('\n');

        // Data rows
        for (final String[] row : formatted) {
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    sb.append(' ');
                }
                final ColumnDef col = columns.get(c);
                if (col.type == ColumnType.STRING || col.fixedValue != null) {
                    sb.append(padRight(row[c], widths[c]));
                } else {
                    sb.append(padLeft(row[c], widths[c]));
                }
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    @NonNull
    private static String formatCell(@NonNull final ColumnDef col, @NonNull final Object value) {
        if (col.fixedValue != null) {
            return col.fixedValue;
        }
        return switch (col.type) {
            case INT -> String.format("%d", ((Number) value).longValue());
            case FLOAT -> String.format("%." + col.precision + "f", ((Number) value).doubleValue());
            case STRING -> String.valueOf(value);
        };
    }

    @NonNull
    private static String padRight(@NonNull final String s, final int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    @NonNull
    private static String padLeft(@NonNull final String s, final int width) {
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }

    /**
     * Starts building a table with the first named column.
     *
     * @param name the header name for the first column
     * @return a new builder
     */
    @NonNull
    public static Builder column(@NonNull final String name) {
        final Builder builder = new Builder();
        return builder.column(name);
    }

    /**
     * Fluent builder for {@link StringTable}.
     */
    public static class Builder {
        private final List<ColumnDef> columns = new ArrayList<>();

        private Builder() {}

        @NonNull
        private ColumnDef lastColumn() {
            return columns.getLast();
        }

        /**
         * Adds a named column.
         *
         * @param name the header name
         * @return this builder
         */
        @NonNull
        public Builder column(@NonNull final String name) {
            final ColumnDef col = new ColumnDef();
            col.header = name;
            columns.add(col);
            return this;
        }

        /**
         * Adds a headless (unnamed) column.
         *
         * @return this builder
         */
        @NonNull
        public Builder column() {
            columns.add(new ColumnDef());
            return this;
        }

        /**
         * Sets the last column's type to float with the given width and precision.
         *
         * @param width     the minimum column width
         * @param precision the number of decimal places
         * @return this builder
         */
        @NonNull
        public Builder typeFloat(final int width, final int precision) {
            lastColumn().type = ColumnType.FLOAT;
            lastColumn().width = width;
            lastColumn().precision = precision;
            return this;
        }

        /**
         * Sets the last column's type to integer with the given width.
         *
         * @param width the minimum column width
         * @return this builder
         */
        @NonNull
        public Builder typeInt(final int width) {
            lastColumn().type = ColumnType.INT;
            lastColumn().width = width;
            return this;
        }

        /**
         * Sets a fixed value for the last column. The column will display this
         * literal in every row and will not consume a value from {@link StringTable#addRow}.
         *
         * @param value the fixed value
         * @return this builder
         */
        @NonNull
        public Builder withFixedValue(@NonNull final String value) {
            lastColumn().fixedValue = value;
            return this;
        }

        /**
         * adds a column with a fixed value
         *
         * @param value the fixed value
         * @return this builder
         */
        @NonNull
        public Builder fixedValue(@NonNull final String value) {
            column();
            lastColumn().fixedValue = value;
            return this;
        }

        /**
         * Builds the {@link StringTable}.
         *
         * @return a new StringTable
         */
        @NonNull
        public StringTable build() {
            return new StringTable(columns);
        }
    }
}
