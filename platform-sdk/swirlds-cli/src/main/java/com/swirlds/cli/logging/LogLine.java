// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import static com.swirlds.cli.logging.HtmlGenerator.BLACKLIST_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.CLASS_NAME_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.ELAPSED_TIME_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.HIDEABLE_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.LOG_LEVEL_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.LOG_LINE_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.LOG_NUMBER_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.MARKER_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.NODE_ID_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.REMAINDER_OF_LINE_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.THREAD_NAME_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.TIMESTAMP_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.WHITELIST_LABEL;
import static com.swirlds.cli.logging.LogProcessingUtils.escapeString;
import static com.swirlds.cli.logging.LogProcessingUtils.getLogLevelColor;
import static com.swirlds.cli.logging.LogProcessingUtils.parseTimestamp;
import static com.swirlds.common.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.common.units.TimeUnit.UNIT_SECONDS;

import com.swirlds.common.formatting.TextEffect;
import com.swirlds.common.formatting.UnitFormat;
import com.swirlds.common.formatting.UnitFormatter;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;

/**
 * Represents a single line of log output
 * <p>
 * 2023-05-18 09:03:03.618     80         INFO    PLATFORM_STATUS    main      SwirldsPlatform: Platform status changed to...
 * timestamp               log number   log level      marker     thread name     class name         remainder of line
 */
public class LogLine implements FormattableString {
    public final Logger logger = LogManager.getLogger();
    public static final String CAPTURED_WHITESPACE_REGEX = "(\\s+)";
    public static final String TIMESTAMP_REGEX = "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+)";
    public static final String LOG_NUMBER_REGEX = "(\\d+)";
    public static final String LOG_LEVEL_REGEX = "(TRACE|DEBUG|INFO|WARN|ERROR|FATAL)";
    public static final String MARKER_REGEX = "([A-Za-z0-9_]+)";
    public static final String THREAD_NAME_REGEX = "(<<?[^>]+>>?)";
    public static final String CLASS_THREAD_NAME_REGEX = "([A-Za-z0-9_]+)";
    public static final String COLON_SPACE_REGEX = "(: )";
    public static final String REMAINDER_OF_LINE_REGEX = "(.*)";

    public static final TextEffect TIMESTAMP_COLOR = TextEffect.GRAY;
    public static final TextEffect LOG_NUMBER_COLOR = TextEffect.WHITE;
    public static final TextEffect LOG_MARKER_COLOR = TextEffect.BRIGHT_BLUE;
    public static final TextEffect THREAD_NAME_COLOR = TextEffect.BRIGHT_WHITE;
    public static final TextEffect CLASS_NAME_COLOR = TextEffect.BRIGHT_CYAN;
    public static final TextEffect HARMLESS_LOG_LEVEL_COLOR = TextEffect.BRIGHT_GREEN;
    public static final TextEffect WARN_LOG_LEVEL_COLOR = TextEffect.BRIGHT_YELLOW;
    public static final TextEffect ERROR_LOG_LEVEL_COLOR = TextEffect.BRIGHT_RED;

    public static final String FULL_REGEX = TIMESTAMP_REGEX
            + CAPTURED_WHITESPACE_REGEX
            + LOG_NUMBER_REGEX
            + CAPTURED_WHITESPACE_REGEX
            + LOG_LEVEL_REGEX
            + CAPTURED_WHITESPACE_REGEX
            + MARKER_REGEX
            + CAPTURED_WHITESPACE_REGEX
            + THREAD_NAME_REGEX
            + CAPTURED_WHITESPACE_REGEX
            + CLASS_THREAD_NAME_REGEX
            + COLON_SPACE_REGEX
            + REMAINDER_OF_LINE_REGEX;

    /**
     * The original log line string
     */
    private final String originalLogString;

    /**
     * The timezone of the timestamp in the log line
     * <p>
     * Currently not in use. Kept for FUTURE WORK
     */
    private final ZoneId zoneId;

    /**
     * The timestamp of the log line, as an instant
     */
    private final Instant timestamp;

    /**
     * The original timestamp string
     */
    private final String timestampOriginalString;

    /**
     * The log number of the log line
     */
    private final String logNumber;

    /**
     * The log level of the log line
     */
    private final String logLevel;

    /**
     * The marker of the log line
     */
    private final String marker;

    /**
     * The thread name of the log line
     */
    private final String threadName;

    /**
     * The class name of the log line
     */
    private final String className;

    /**
     * ": "
     */
    private final String colonSpace;

    /**
     * The remainder of the log line that follows the colonSpace
     */
    private final FormattableString remainderOfLine;

    /**
     * The list of whitespace strings that were captured by the regex
     */
    private final List<String> whitespaces = new ArrayList<>();

    /**
     * The node ID of the node that generated this log line. May be null if node ID wasn't specified.
     */
    private final NodeId nodeId;

    /**
     * The start time of the log file that this log line is from. May be null if log start time wasn't specified.
     */
    private Instant logStartTime = null;

    /**
     * Some logs contain newline characters, which are not parseable by the standard regex.
     * Therefore, any line that doesn't match the standard format will be added as a non-standard log here
     */
    private NonStandardLog additionalLines;

    /**
     * Construct a new LogLine from a log line string.
     *
     * @param logLineString the log line string
     * @param zoneId        the zone ID of the timestamp in the log line
     * @param nodeId        the node ID of the node that generated this log line
     */
    public LogLine(@NonNull final String logLineString, @NonNull final ZoneId zoneId, @Nullable final NodeId nodeId) {

        this.originalLogString = Objects.requireNonNull(logLineString);
        this.zoneId = Objects.requireNonNull(zoneId);
        this.nodeId = nodeId;

        final Matcher logLineMatcher = Pattern.compile(FULL_REGEX).matcher(logLineString);

        if (!logLineMatcher.matches()) {
            throw new IllegalArgumentException("Log line string does not match expected format: " + logLineString);
        }

        timestampOriginalString = logLineMatcher.group(1);
        timestamp = parseTimestamp(timestampOriginalString, zoneId);
        whitespaces.add(logLineMatcher.group(2));
        logNumber = logLineMatcher.group(3);
        whitespaces.add(logLineMatcher.group(4));
        logLevel = logLineMatcher.group(5);
        whitespaces.add(logLineMatcher.group(6));
        marker = logLineMatcher.group(7);
        whitespaces.add(logLineMatcher.group(8));
        threadName = logLineMatcher.group(9);
        whitespaces.add(logLineMatcher.group(10));
        className = logLineMatcher.group(11);
        colonSpace = logLineMatcher.group(12);

        final String remainderString = logLineMatcher.group(13);

        switch (marker) {
            case "PLATFORM_STATUS" -> remainderOfLine = new PlatformStatusLog(remainderString);
            default -> remainderOfLine = new DefaultFormattableString(remainderString);
        }
    }

    /**
     * Constructor without node id
     *
     * @param logLineString the log line string
     * @param zoneId        the zone ID of the timestamp in the log line
     */
    public LogLine(@NonNull final String logLineString, @NonNull final ZoneId zoneId) {
        this(logLineString, zoneId, null);
    }

    /**
     * The earliest timestamp of any log line in the system
     *
     * @param logStartTime the log starting time
     */
    public void setLogStartTime(@NonNull final Instant logStartTime) {
        this.logStartTime = Objects.requireNonNull(logStartTime);
    }

    /**
     * Add a non-standard line to this log line. Since non-standard lines lack the necessary metadata for filtering,
     * they must belong to standard line.
     */
    public void addNonStandardLine(@NonNull final String line) {
        if (additionalLines == null) {
            additionalLines = new NonStandardLog();
        }
        additionalLines.addLogText(line);
    }

    /**
     * Get the node ID of the node that generated this log line
     * <p>
     * May be null if node ID wasn't specified.
     *
     * @return the node ID
     */
    @Nullable
    public NodeId getNodeId() {
        return nodeId;
    }

    /**
     * Get the timestamp of the log line
     *
     * @return the timestamp
     */
    @NonNull
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Get the log number of the log line
     *
     * @return the log number
     */
    @NonNull
    public String getLogNumber() {
        return logNumber;
    }

    /**
     * Get the level of the log line
     *
     * @return the log level
     */
    @NonNull
    public String getLogLevel() {
        return logLevel;
    }

    /**
     * Get the marker of the log line
     *
     * @return the marker
     */
    @NonNull
    public String getMarker() {
        return marker;
    }

    /**
     * Get the thread name of the log line
     *
     * @return the thread name
     */
    @NonNull
    public String getThreadName() {
        return threadName;
    }

    /**
     * Get the class name of the log line
     *
     * @return the class name
     */
    @NonNull
    public String getClassName() {
        return className;
    }

    /**
     * Get the formattable string remainder of the log line
     *
     * @return the remainder of the log line
     */
    @NonNull
    public FormattableString getRemainderOfLine() {
        return remainderOfLine;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getOriginalPlaintext() {
        return originalLogString;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String generateAnsiString() {
        int whitespaceIndex = 0;

        return TIMESTAMP_COLOR.apply(timestampOriginalString)
                + whitespaces.get(whitespaceIndex++)
                + LOG_NUMBER_COLOR.apply(logNumber)
                + whitespaces.get(whitespaceIndex++)
                + getLogLevelColor(logLevel).apply(logLevel)
                + whitespaces.get(whitespaceIndex++)
                + LOG_MARKER_COLOR.apply(marker)
                + whitespaces.get(whitespaceIndex++)
                + THREAD_NAME_COLOR.apply(threadName)
                + whitespaces.get(whitespaceIndex)
                + CLASS_NAME_COLOR.apply(className)
                + colonSpace
                + remainderOfLine.generateAnsiString();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String generateHtmlString() {
        // the actual value of these elements of the line become html classes for the line itself, so that we can filter
        // based on these values
        final List<String> rowClassNames = Stream.of(
                        logLevel,
                        marker,
                        threadName,
                        className,
                        nodeId == null ? "" : "node" + nodeId,
                        HIDEABLE_LABEL,
                        LOG_LINE_LABEL)
                .map(LogProcessingUtils::escapeString)
                .toList();

        final List<String> dataCellTags = new ArrayList<>();

        final String selectCheckbox = new HtmlTagFactory("input")
                .addClass("select-checkbox")
                .addClasses(rowClassNames)
                .addAttribute("type", "checkbox")
                .generateTag();
        final HtmlTagFactory selectCheckboxCellFactory = new HtmlTagFactory("td", selectCheckbox);
        dataCellTags.add(selectCheckboxCellFactory.generateTag());

        final HtmlTagFactory nodeIdTagFactory = new HtmlTagFactory(
                        "td", nodeId == null ? "" : "node" + escapeString(nodeId.toString()))
                .addClasses(List.of(NODE_ID_COLUMN_LABEL, HIDEABLE_LABEL, nodeId == null ? "" : "node-" + nodeId))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(nodeIdTagFactory.generateTag());

        final String elapsedTimeString;
        if (logStartTime == null) {
            elapsedTimeString = "";
        } else {
            final UnitFormatter unitFormatter = new UnitFormatter(
                            timestamp.toEpochMilli() - logStartTime.toEpochMilli(), UNIT_MILLISECONDS)
                    .setUnitFormat(UnitFormat.MULTI_SIMPLIFIED)
                    .setDecimalPlaces(3)
                    .setLowestUnit(UNIT_SECONDS)
                    .setShowSpaceInBetween(false);
            elapsedTimeString = escapeString(unitFormatter.render());
        }

        final HtmlTagFactory logStartTimeTagFactory = new HtmlTagFactory("td", elapsedTimeString)
                .addClasses(List.of(ELAPSED_TIME_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(logStartTimeTagFactory.generateTag());

        final HtmlTagFactory timestampTagFactory = new HtmlTagFactory("td", escapeString(timestampOriginalString))
                .addClasses(List.of(TIMESTAMP_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(timestampTagFactory.generateTag());

        final HtmlTagFactory logNumberTagFactory = new HtmlTagFactory("td", escapeString(logNumber))
                .addClasses(List.of(LOG_NUMBER_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(logNumberTagFactory.generateTag());

        final HtmlTagFactory logLevelTagFactory = new HtmlTagFactory("td", escapeString(logLevel))
                .addClasses(List.of(LOG_LEVEL_COLUMN_LABEL, HIDEABLE_LABEL, logLevel + "-level"))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(logLevelTagFactory.generateTag());

        final HtmlTagFactory markerTagFactory = new HtmlTagFactory("td", escapeString(marker))
                .addClasses(List.of(MARKER_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(markerTagFactory.generateTag());

        final HtmlTagFactory threadNameTagFactory = new HtmlTagFactory("td", escapeString(threadName))
                .addClasses(List.of(THREAD_NAME_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(threadNameTagFactory.generateTag());

        final HtmlTagFactory classNameTagFactory = new HtmlTagFactory("td", escapeString(className) + colonSpace)
                .addClasses(List.of(CLASS_NAME_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(classNameTagFactory.generateTag());

        final HtmlTagFactory remainderOfLineTagFactory = new HtmlTagFactory(
                        "td",
                        // don't escape this string, since it's assumed generateHtmlString returns a string already
                        // escaped
                        remainderOfLine.generateHtmlString())
                .addClasses(List.of(REMAINDER_OF_LINE_COLUMN_LABEL, HIDEABLE_LABEL))
                .addAttribute(BLACKLIST_LABEL, "0")
                .addAttribute(WHITELIST_LABEL, "0");
        dataCellTags.add(remainderOfLineTagFactory.generateTag());

        final HtmlTagFactory mainLogRowFactory =
                new HtmlTagFactory("tr", "\n" + String.join("\n", dataCellTags) + "\n").addClass(LOG_LINE_LABEL);

        if (additionalLines == null) {
            mainLogRowFactory
                    .addClasses(rowClassNames)
                    .addAttribute(BLACKLIST_LABEL, "0")
                    .addAttribute(WHITELIST_LABEL, "0");

            return mainLogRowFactory.generateTag();
        } else {
            return new HtmlTagFactory(
                            "tbody", mainLogRowFactory.generateTag() + "\n" + additionalLines.generateHtmlString())
                    .addClass(LOG_LINE_LABEL)
                    .addClasses(rowClassNames)
                    .addAttribute(BLACKLIST_LABEL, "0")
                    .addAttribute(WHITELIST_LABEL, "0")
                    .generateTag();
        }
    }
}
