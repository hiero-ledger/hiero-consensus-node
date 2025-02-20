// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_CENTER;
import static com.swirlds.common.formatting.HorizontalAlignment.ALIGNED_LEFT;
import static com.swirlds.common.formatting.StringFormattingUtils.repeatedChar;
import static com.swirlds.common.formatting.TextEffect.applyEffects;
import static com.swirlds.common.formatting.TextEffect.getPrintableTextLength;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <p>
 * Utility class for formatting and printing an ASCII table.
 * </p>
 *
 * <p>
 * See the TextEffect-Colors.png for a screenshot of all effects rendered by intellij on a macbook.
 * </p>
 */
public class TextTable {

    private static final char PADDING = ' ';
    private static final char CROSS_JUNCTION = '┼';
    private static final char LEFT_JUNCTION = '┠';
    private static final char LEFT_HEADER_JUNCTION = '┣';
    private static final char RIGHT_JUNCTION = '┨';
    private static final char RIGHT_HEADER_JUNCTION = '┫';
    private static final char BOTTOM_JUNCTION = '┷';
    private static final char TOP_JUNCTION = '┯';
    private static final char TOP_LEFT_CORNER = '┏';
    private static final char TOP_RIGHT_CORNER = '┓';
    private static final char BOTTOM_LEFT_CORNER = '┗';
    private static final char BOTTOM_RIGHT_CORNER = '┛';
    private static final char HORIZONTAL_BAR = '─';
    private static final char THICK_HORIZONTAL_BAR = '━';
    private static final char VERTICAL_BAR = '│';
    private static final char THICK_VERTICAL_BAR = '┃';
    private static final char NEWLINE = '\n';

    private String title;
    private final List<List<String>> rows = new ArrayList<>();
    private boolean bordersEnabled = true;
    private int extraPadding = 0;

    /**
     * Describes the position of a cell.
     */
    private record Cell(int row, int column) {}

    private final Map<Integer, List<TextEffect>> rowEffects = new HashMap<>();
    private final Map<Integer, List<TextEffect>> columnEffects = new HashMap<>();
    private final Map<Cell, List<TextEffect>> cellEffects = new HashMap<>();
    private final List<TextEffect> titleEffects = new ArrayList<>();
    private final List<TextEffect> globalCellEffects = new ArrayList<>();
    private final List<TextEffect> borderEffects = new ArrayList<>();

    private final Map<Integer, HorizontalAlignment> rowHorizontalAlignments = new HashMap<>();
    private final Map<Integer, HorizontalAlignment> columnHorizontalAlignments = new HashMap<>();
    private final Map<Cell, HorizontalAlignment> cellHorizontalAlignments = new HashMap<>();
    private HorizontalAlignment titleHorizontalAlignment = ALIGNED_CENTER;
    private HorizontalAlignment globalHorizontalAlignment = ALIGNED_LEFT;

    /**
     * Create a new text table.
     */
    public TextTable() {}

    /**
     * Enable or disable borders.
     *
     * @param bordersEnabled
     * 		whether borders should be enabled
     * @return this object
     */
    @NonNull
    public TextTable setBordersEnabled(final boolean bordersEnabled) {
        this.bordersEnabled = bordersEnabled;
        return this;
    }

    /**
     * Enable extra padding for each column.
     *
     * @param extraPadding
     * 		the number of extra spaces to add to each column
     * @return this object
     */
    @NonNull
    public TextTable setExtraPadding(final int extraPadding) {
        this.extraPadding = extraPadding;
        return this;
    }

    /**
     * Set the title of the table.
     *
     * @param title
     * 		the title of the table
     * @return this object
     */
    @NonNull
    public TextTable setTitle(@NonNull final String title) {
        this.title = title;
        return this;
    }

    /**
     * Add a row to the table.
     *
     * @param row
     * 		a single row
     * @return this object
     */
    @SuppressWarnings("DuplicatedCode")
    @NonNull
    public TextTable addRow(@Nullable final Object... row) {
        if (row != null) {
            final List<String> rowString = new ArrayList<>();

            for (final Object o : row) {
                rowString.add(o == null ? "null" : o.toString());
            }

            rows.add(rowString);
        }

        return this;
    }

    /**
     * Add to the current row.
     *
     * @param elements
     * 		the elements to add to the current row
     * @return this object
     */
    @NonNull
    public TextTable addToRow(@NonNull final Object... elements) {
        final List<String> row;
        if (rows.isEmpty()) {
            row = new ArrayList<>();
            rows.add(row);
        } else {
            row = rows.get(rows.size() - 1);
        }

        for (final Object o : elements) {
            row.add(o == null ? "null" : o.toString());
        }

        return this;
    }

    /**
     * <p>
     * Add text effects to a row. Has no effect if there is no data in the specified row when the table is rendered.
     * </p>
     *
     * <p>
     * Effects are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell</li>
     * </ol>
     *
     * @param row
     * 		the target row
     * @param effects
     * 		zero or more effects to add for the row
     * @return this object
     */
    @NonNull
    public TextTable addRowEffects(final int row, @Nullable final TextEffect... effects) {
        if (effects == null) {
            return this;
        }

        final List<TextEffect> effectList = rowEffects.computeIfAbsent(row, k -> new ArrayList<>());
        effectList.addAll(Arrays.asList(effects));

        return this;
    }

    /**
     * <p>
     * Add text effects to a column. Has no effect if there is no data in the specified column when the table is
     * rendered.
     * </p>
     *
     * <p>
     * Effects are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param column
     * 		the target column
     * @param effects
     * 		zero or more effects to add for the column
     * @return this object
     */
    @NonNull
    public TextTable addColumnEffects(final int column, @Nullable final TextEffect... effects) {
        if (effects == null) {
            return this;
        }

        final List<TextEffect> effectList = columnEffects.computeIfAbsent(column, k -> new ArrayList<>());
        effectList.addAll(Arrays.asList(effects));

        return this;
    }

    /**
     * <p>
     * Add text effects to a cell. Has no effect if there is no data in the specified cell when the table is rendered.
     * </p>
     *
     * <p>
     * Effects are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param row
     * 		the target row
     * @param column
     * 		the target column
     * @param effects
     * 		zero or more effects to add for the cell
     * @return this object
     */
    @NonNull
    public TextTable addCellEffects(final int row, final int column, @Nullable final TextEffect... effects) {
        if (effects == null) {
            return this;
        }

        final Cell cell = new Cell(row, column);

        final List<TextEffect> effectList = cellEffects.computeIfAbsent(cell, k -> new ArrayList<>());
        effectList.addAll(Arrays.asList(effects));

        return this;
    }

    /**
     * Add text effects to the title.
     *
     * @param effects
     * 		zero or more effects to add to the title
     * @return this object
     */
    @NonNull
    public TextTable addTitleEffects(@Nullable final TextEffect... effects) {
        if (effects == null) {
            return this;
        }
        titleEffects.addAll(Arrays.asList(effects));
        return this;
    }

    /**
     * <p>
     * Add text effects to all cells. Does not affect the header, title, or border.
     * </p>
     *
     * <p>
     * Effects are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param effects
     * 		zero or more effects to add to all cells
     * @return this object
     */
    @NonNull
    public TextTable addGlobalCellEffects(@Nullable final TextEffect... effects) {
        if (effects == null) {
            return this;
        }
        globalCellEffects.addAll(Arrays.asList(effects));
        return this;
    }

    /**
     * Add text effects to cell borders.
     *
     * @param effects
     * 		zero or more effects to add to borders
     * @return this object
     */
    @NonNull
    public TextTable addBorderEffects(@Nullable final TextEffect... effects) {
        if (effects == null) {
            return this;
        }
        borderEffects.addAll(Arrays.asList(effects));
        return this;
    }

    /**
     * <p>
     * Set the horizontal alignment for a row.
     * </p>
     *
     * <p>
     * Alignments are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param row
     * 		the row index
     * @param alignment
     * 		the alignment of the row
     * @return this object
     */
    @NonNull
    public TextTable setRowHorizontalAlignment(final int row, @NonNull final HorizontalAlignment alignment) {
        Objects.requireNonNull(alignment, "alignment must not be null");
        rowHorizontalAlignments.put(row, alignment);
        return this;
    }

    /**
     * <p>
     * Set the horizontal alignment for a column.
     * </p>
     *
     * <p>
     * Alignments are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param column
     * 		the column index
     * @param alignment
     * 		the alignment of the column
     * @return this object
     */
    @NonNull
    public TextTable setColumnHorizontalAlignment(final int column, @NonNull final HorizontalAlignment alignment) {
        Objects.requireNonNull(alignment, "alignment must not be null");
        columnHorizontalAlignments.put(column, alignment);
        return this;
    }

    /**
     * <p>
     * Set the horizontal alignment for a cell.
     * </p>
     *
     * <p>
     * Alignments are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param row
     * 		the row index
     * @param column
     * 		the column index
     * @param alignment
     * 		the alignment of the cell
     * @return this object
     */
    @NonNull
    public TextTable setCellHorizontalAlignment(
            final int row, final int column, @NonNull final HorizontalAlignment alignment) {
        Objects.requireNonNull(alignment, "alignment must not be null");
        cellHorizontalAlignments.put(new Cell(row, column), alignment);
        return this;
    }

    /**
     * Set the horizontal alignment for the title. If not specified then the global alignment is used.
     *
     * @param alignment
     * 		the alignment of the title
     * @return this object
     */
    @NonNull
    public TextTable setTitleHorizontalAlignment(@NonNull final HorizontalAlignment alignment) {
        Objects.requireNonNull(alignment, "alignment must not be null");
        titleHorizontalAlignment = alignment;
        return this;
    }

    /**
     * <p>
     * Set the default horizontal alignment for the entire table
     * </p>
     *
     * <p>
     * Alignments are applied in the following order:
     * </p>
     * <ol>
     * <li> global </li>
     * <li> row </li>
     * <li> column </li>
     * <li> cell </li>
     * </ol>
     *
     * @param alignment
     * 		the alignment for the entire table
     * @return this object
     */
    @NonNull
    public TextTable setGlobalHorizontalAlignment(@NonNull final HorizontalAlignment alignment) {
        Objects.requireNonNull(alignment, "alignment must not be null");
        globalHorizontalAlignment = alignment;
        return this;
    }

    /**
     * Format cell data.
     */
    @NonNull
    private String formatCellData(final int row, final int column, @NonNull final String cellData) {
        final List<TextEffect> effects = new ArrayList<>(globalCellEffects);
        if (rowEffects.containsKey(row)) {
            effects.addAll(rowEffects.get(row));
        }
        if (columnEffects.containsKey(column)) {
            effects.addAll(columnEffects.get(column));
        }
        final Cell cell = new Cell(row, column);
        if (cellEffects.containsKey(cell)) {
            effects.addAll(cellEffects.get(cell));
        }

        if (effects.isEmpty()) {
            return cellData;
        } else {
            return TextEffect.applyEffects(cellData, effects);
        }
    }

    /**
     * Format a cell with left/right alignment.
     */
    private void alignCellData(
            @NonNull final StringBuilder sb,
            final int row,
            final int column,
            @NonNull final String cellData,
            final int desiredWidth,
            final boolean isLastColumn) {

        final HorizontalAlignment alignment;

        final Cell cell = new Cell(row, column);

        if (cellHorizontalAlignments.containsKey(cell)) {
            alignment = cellHorizontalAlignments.get(cell);
        } else if (columnHorizontalAlignments.containsKey(column)) {
            alignment = columnHorizontalAlignments.get(column);
        } else if (rowHorizontalAlignments.containsKey(row)) {
            alignment = rowHorizontalAlignments.get(row);
        } else {
            alignment = globalHorizontalAlignment;
        }

        final boolean trailingPadding = bordersEnabled || !isLastColumn;

        alignment.pad(sb, cellData, ' ', desiredWidth, trailingPadding);
    }

    /**
     * Format a border character(s) and write it to a string builder.
     */
    private void writeBorder(@NonNull final StringBuilder sb, @NonNull final String border) {
        if (borderEffects.isEmpty()) {
            sb.append(border);
        } else {
            applyEffects(sb, border, borderEffects);
        }
    }

    /**
     * Format a border character and write it to a string builder.
     */
    private void writeBorder(@NonNull final StringBuilder sb, final char border) {
        writeBorder(sb, String.valueOf(border));
    }

    /**
     * Expand planned column widths to fit a given row. After all rows have been processed this way,
     * the column widths list will contain the proper width for each column.
     *
     * @param row
     * 		the row that needs to be fitted into the table
     */
    private static void expandColumnWidthsForRow(
            @NonNull final List<Integer> columnWidths, @NonNull final List<String> row) {
        for (int column = 0; column < row.size(); column++) {

            final int columnWidth = getPrintableTextLength(row.get(column));

            if (columnWidths.size() <= column) {
                columnWidths.add(columnWidth);
            } else {
                columnWidths.set(column, Math.max(columnWidths.get(column), columnWidth));
            }
        }
    }

    /**
     * Compute the width for each column.
     *
     * @return a list of widths indexed by column
     */
    private List<Integer> computeColumnWidths() {
        final List<Integer> columnWidths = new ArrayList<>();

        for (final List<String> row : rows) {
            expandColumnWidthsForRow(columnWidths, row);
        }

        return columnWidths;
    }

    /**
     * Generate the top of the table.
     */
    private void generateTopLine(
            @NonNull final StringBuilder sb, @NonNull final List<Integer> columnWidths, final int columnWidthSum) {

        if (!bordersEnabled) {
            return;
        }

        writeBorder(sb, TOP_LEFT_CORNER);

        if (title != null) {
            writeBorder(sb, repeatedChar(THICK_HORIZONTAL_BAR, columnWidthSum + columnWidths.size() * 3 - 1));
        } else {

            for (int columnIndex = 0; columnIndex < columnWidths.size(); columnIndex++) {
                writeBorder(sb, repeatedChar(THICK_HORIZONTAL_BAR, columnWidths.get(columnIndex) + 2 + extraPadding));
                if (columnIndex + 1 < columnWidths.size()) {
                    writeBorder(sb, TOP_JUNCTION);
                }
            }
        }

        writeBorder(sb, TOP_RIGHT_CORNER);
        sb.append(NEWLINE);
    }

    /**
     * Generate the line containing the title.
     */
    private void generateTitleLine(@NonNull final StringBuilder sb, final int columnWidthSum, final int columnCount) {
        if (title == null) {
            return;
        }

        if (bordersEnabled) {
            writeBorder(sb, THICK_VERTICAL_BAR);
            sb.append(PADDING);
        }

        final String formattedTitle = applyEffects(title, titleEffects);

        final int titleWidth;
        if (bordersEnabled) {
            titleWidth = columnWidthSum + columnCount * 3 - 3;
        } else {
            titleWidth = columnWidthSum + columnCount * 3 - 1;
        }

        titleHorizontalAlignment.pad(sb, formattedTitle, ' ', titleWidth, bordersEnabled);

        if (bordersEnabled) {
            sb.append(PADDING);
            writeBorder(sb, THICK_VERTICAL_BAR);
            sb.append(NEWLINE);
        }
    }

    /**
     * Generate the line between the title and the headers.
     */
    private void generateLineBelowTitle(@NonNull final StringBuilder sb, @NonNull final List<Integer> columnWidths) {
        if (title == null || !bordersEnabled) {
            return;
        }

        writeBorder(sb, LEFT_HEADER_JUNCTION);
        for (int columnIndex = 0; columnIndex < columnWidths.size(); columnIndex++) {
            writeBorder(sb, repeatedChar(THICK_HORIZONTAL_BAR, columnWidths.get(columnIndex) + 2 + extraPadding));
            if (columnIndex + 1 < columnWidths.size()) {
                writeBorder(sb, TOP_JUNCTION);
            }
        }
        writeBorder(sb, RIGHT_HEADER_JUNCTION);
        sb.append(NEWLINE);
    }

    /**
     * Generate a row containing column data.
     */
    private void generateDataRow(
            @NonNull final StringBuilder sb, final int row, @NonNull final List<Integer> columnWidths) {

        final List<String> rowData = rows.get(row);

        if (bordersEnabled) {
            writeBorder(sb, THICK_VERTICAL_BAR);
        }

        for (int column = 0; column < columnWidths.size(); column++) {

            if (bordersEnabled) {
                sb.append(PADDING);
            }

            final String cellData = column < rowData.size() ? rowData.get(column) : "";
            final String formattedCellData = formatCellData(row, column, cellData);
            final boolean isLastColumn = column == columnWidths.size() - 1;
            alignCellData(sb, row, column, formattedCellData, columnWidths.get(column), isLastColumn);

            if (bordersEnabled || !isLastColumn) {
                sb.append(repeatedChar(PADDING, extraPadding + 1));
            }
            if (bordersEnabled && column + 1 < columnWidths.size()) {
                writeBorder(sb, VERTICAL_BAR);
            }
        }

        if (bordersEnabled) {
            writeBorder(sb, THICK_VERTICAL_BAR);
        }
        sb.append(NEWLINE);
    }

    /**
     * Generate the line below a row containing data.
     */
    private void generateLineBelowDataRow(@NonNull final StringBuilder sb, @NonNull final List<Integer> columnWidths) {
        if (!bordersEnabled) {
            return;
        }

        writeBorder(sb, LEFT_JUNCTION);

        for (int columnIndex = 0; columnIndex < columnWidths.size(); columnIndex++) {
            writeBorder(sb, repeatedChar(HORIZONTAL_BAR, columnWidths.get(columnIndex) + 2 + extraPadding));
            if (columnIndex + 1 < columnWidths.size()) {
                writeBorder(sb, CROSS_JUNCTION);
            }
        }
        writeBorder(sb, RIGHT_JUNCTION);
        sb.append(NEWLINE);
    }

    /**
     * Generate the rows in the table.
     */
    private void generateRows(@NonNull final StringBuilder sb, @NonNull final List<Integer> columnWidths) {
        for (int row = 0; row < rows.size(); row++) {
            generateDataRow(sb, row, columnWidths);

            // Line below row
            if (row + 1 < rows.size()) {
                generateLineBelowDataRow(sb, columnWidths);
            }
        }
    }

    /**
     * Generate the last line in the table.
     */
    private void generateBottomLine(@NonNull final StringBuilder sb, @NonNull final List<Integer> columnWidths) {
        if (!bordersEnabled) {
            return;
        }

        writeBorder(sb, BOTTOM_LEFT_CORNER);

        for (int columnIndex = 0; columnIndex < columnWidths.size(); columnIndex++) {
            writeBorder(sb, repeatedChar(THICK_HORIZONTAL_BAR, columnWidths.get(columnIndex) + 2 + extraPadding));
            if (columnIndex + 1 < columnWidths.size()) {
                writeBorder(sb, BOTTOM_JUNCTION);
            }
        }

        writeBorder(sb, BOTTOM_RIGHT_CORNER);
    }

    /**
     * If the title is really long then expand the last column to fill the space.
     */
    private void expandLastColumnIfNeeded(@NonNull final List<Integer> columnWidths) {
        if (title == null) {
            return;
        }

        int columnWidthSum = 0;
        for (final int columnWidth : columnWidths) {
            columnWidthSum += columnWidth;
        }

        final int titleLength = getPrintableTextLength(title);
        final int minimumWidth = titleLength + columnWidths.size() * 3 - 3;
        if (columnWidthSum < minimumWidth) {
            // Title is too wide, expand a column to balance it out

            final int expansion = minimumWidth - columnWidthSum;
            final int lastIndex = columnWidths.size() - 1;
            columnWidths.set(lastIndex, columnWidths.get(lastIndex) + expansion);
        }
    }

    /**
     * Render this table to a string builder.
     *
     * @param sb
     * 		the string builder to add to
     */
    public void render(@NonNull final StringBuilder sb) {
        final List<Integer> columnWidths = computeColumnWidths();
        expandLastColumnIfNeeded(columnWidths);

        int columnWidthSum = 0;
        for (final int columnWidth : columnWidths) {
            columnWidthSum += columnWidth + extraPadding;
        }

        generateTopLine(sb, columnWidths, columnWidthSum);
        generateTitleLine(sb, columnWidthSum, columnWidths.size());
        generateLineBelowTitle(sb, columnWidths);
        generateRows(sb, columnWidths);
        generateBottomLine(sb, columnWidths);
    }

    /**
     * Render this table to a string.
     *
     * @return the rendered table
     */
    @NonNull
    public String render() {
        final StringBuilder sb = new StringBuilder();
        render(sb);
        return sb.toString();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String toString() {
        return render();
    }
}
