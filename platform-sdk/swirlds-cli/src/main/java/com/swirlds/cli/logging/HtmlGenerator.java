// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import static com.swirlds.cli.logging.HtmlColors.getHtmlColor;
import static com.swirlds.cli.logging.LogLine.CLASS_NAME_COLOR;
import static com.swirlds.cli.logging.LogLine.LOG_MARKER_COLOR;
import static com.swirlds.cli.logging.LogLine.LOG_NUMBER_COLOR;
import static com.swirlds.cli.logging.LogLine.THREAD_NAME_COLOR;
import static com.swirlds.cli.logging.LogLine.TIMESTAMP_COLOR;
import static com.swirlds.cli.logging.LogProcessingUtils.getLogLevelColor;
import static com.swirlds.cli.logging.PlatformStatusLog.STATUS_HTML_CLASS;

import com.swirlds.common.formatting.TextEffect;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.apache.logging.log4j.Level;
import org.hiero.consensus.model.node.NodeId;

/**
 * Generates an HTML log page
 */
public class HtmlGenerator {
    // These values have been chosen to mimic the jetbrains terminal
    /**
     * This is a dark gray color
     */
    public static final String PAGE_BACKGROUND_COLOR = "#1e1e23";

    public static final String HIGHLIGHT_COLOR = "#353539";

    /**
     * This is a light gray color
     */
    public static final String DEFAULT_TEXT_COLOR = "#bdbfc4";

    public static final String WHITELIST_RADIO_COLOR = "#6FD154";
    public static final String NEUTRALLIST_RADIO_COLOR = "#F3D412";
    public static final String BLACKLIST_RADIO_COLOR = "#DA4754";

    /**
     * Jetbrains font
     */
    public static final String DEFAULT_FONT = "Jetbrains Mono, monospace";

    /**
     * The source for the minified jQuery library
     */
    public static final String MIN_JS_SOURCE = "https://ajax.googleapis.com/ajax/libs/jquery/3.6.4/jquery.min.js";

    /**
     * HTML elements with this class can be hidden with the filter checkboxes
     */
    public static final String HIDEABLE_LABEL = "hideable";

    /**
     * This label is used to hold the value of how many blacklist filters are currently applied to a field
     */
    public static final String BLACKLIST_LABEL = "blacklist";

    /**
     * This label is for fields we absolutely positively do not want displayed, regardless of what filters say
     * <p>
     * The only thing that supersedes this are the "select" checkboxes
     */
    public static final String NO_SHOW = "no-show";

    /**
     * This label is used to hold the value of how many whitelist filters are currently applied to a field
     */
    public static final String WHITELIST_LABEL = "whitelist";

    public static final String LOG_LINE_LABEL = "log-line";
    public static final String NODE_ID_COLUMN_LABEL = "node-id";
    public static final String ELAPSED_TIME_COLUMN_LABEL = "elapsed-time";
    public static final String TIMESTAMP_COLUMN_LABEL = "timestamp";
    public static final String LOG_NUMBER_COLUMN_LABEL = "log-number";
    public static final String LOG_LEVEL_COLUMN_LABEL = "log-level";
    public static final String MARKER_COLUMN_LABEL = "marker";
    public static final String THREAD_NAME_COLUMN_LABEL = "thread-name";
    public static final String CLASS_NAME_COLUMN_LABEL = "class-name";
    public static final String REMAINDER_OF_LINE_COLUMN_LABEL = "remainder";
    public static final String NON_STANDARD_LABEL = "non-standard";

    public static final String SELECT_MANY_BUTTON_LABEL = "select-many-button";
    public static final String DESELECT_MANY_BUTTON_LABEL = "deselect-many-button";
    public static final String SECLECT_COLUMN_BUTTON_LABEL = "select-column-button";
    public static final String SELECT_COMPACT_BUTTON_LABEL = "select-compact-button";

    /**
     * Signifies filter radio buttons
     */
    public static final String FILTER_RADIO_LABEL = "filter-radio";

    public static final String WHITELIST_RADIO_LABEL = "whitelist-radio";
    public static final String NEUTRALLIST_RADIO_LABEL = "neutrallist-radio";
    public static final String BLACKLIST_RADIO_LABEL = "blacklist-radio";

    /**
     * Signifies filter checkboxes
     */
    public static final String FILTER_CHECKBOX_LABEL = "filter-checkbox";

    /**
     * Signifies checkboxes where unchecked results in ABSOLUTELY_NO_SHOW, rather than simple blacklist
     */
    public static final String NO_SHOW_CHECKBOX_LABEL = "no-show-checkbox";

    /**
     * This label is used to make the filter column and log table full height, side by side
     */
    public static final String DOUBLE_COLUMNS_DIV_LABEL = "double-columns";

    /**
     * This label is used to make the filter column and log table scroll independently
     */
    public static final String INDEPENDENT_SCROLL_LABEL = "independent-scroll";
    /**
     * For styling particular to the log table instance of an independent scroll box
     */
    public static final String TABLE_INDEPENDENT_SCROLL_LABEL = "table-independent-scroll";

    public static final String LOG_TABLE_LABEL = "log-table";

    /**
     * The labels to use for styling log levels.
     */
    private static final Map<String, String> logLevelLabels = Map.of(
            "TRACE", "trace-label",
            "DEBUG", "debug-label",
            "INFO", "info-label",
            "WARN", "warn-label",
            "ERROR", "error-label",
            "FATAL", "fatal-label");

    /**
     * The javascript that is used to hide/show elements when the filter checkboxes are clicked
     */
    public static final String FILTER_JS =
            """
            // the radio buttons that have the ability to hide things
            let filterRadios = document.getElementsByClassName("filter-radio");

            // create a map from radio name to previous value
            let previousValues = new Map();

            // add a listener to each radio button
            for (const element of filterRadios) {
                // set defaults values to neutral
                previousValues.set($(element).attr("name"), "2");

                element.addEventListener("change", function() {
                    // the classes that exist on the checkbox that is clicked
                    let radioClasses = this.classList;

                    // the name of the class that should be hidden
                    let toggleClass;

                    // each radio button has 3 classes, "filter-radio", the type of radio button this is, and the name of the class to be hidden
                    for (const element of radioClasses) {
                        if (element === "filter-radio" || element.endsWith("filter-section")) {
                            continue;
                        }

                        toggleClass = element;
                    }

                    let newCheckedValue = $(this).filter(":checked").val();
                    let previousCheckedValue = previousValues.get($(this).attr("name"));

                    // record the current value of the radio button that was clicked
                    previousValues.set($(this).attr("name"), $(this).filter(":checked").val());

                    // these are the objects on the page which match the class to toggle (discluding the input boxes)
                    let matchingObjects = $("." + toggleClass).not("input");

                    // go through each of the matching objects, and modify the hide count according to the value of the checkbox
                    for (const element of matchingObjects) {
                        let currentBlacklistCount = parseInt($(element).attr('blacklist')) || 0;
                        let currentWhitelistCount = parseInt($(element).attr('whitelist')) || 0;
                        let currentNoShowCount = parseInt($(element).attr('no-show')) || 0;

                        let newBlacklistCount;
                        let newWhitelistCount;
                        let newNoShowCount;

                        // modify blacklist and whitelist counts depending on the new checked value, and the previous checked value
                        if (newCheckedValue === "1") {
                            newWhitelistCount = currentWhitelistCount + 1;
                            if (previousCheckedValue === "3") {
                                newBlacklistCount = currentBlacklistCount - 1;
                            } else if(previousCheckedValue === "4") {
                                newNoShowCount = currentNoShowCount - 1;
                            }
                        } else if (newCheckedValue === "2") {
                            if (previousCheckedValue === "1") {
                                newWhitelistCount = currentWhitelistCount - 1;
                            } else if (previousCheckedValue === "3") {
                                newBlacklistCount = currentBlacklistCount - 1;
                            } else if(previousCheckedValue === "4") {
                                newNoShowCount = currentNoShowCount - 1;
                            }
                        } else if (newCheckedValue === "3") {
                            newBlacklistCount = currentBlacklistCount + 1;
                            if (previousCheckedValue === "1") {
                                newWhitelistCount = currentWhitelistCount - 1;
                            } else if(previousCheckedValue === "4") {
                                newNoShowCount = currentNoShowCount - 1;
                            }
                        } else if (newCheckedValue === "4") {
                            newNoShowCount = currentNoShowCount + 1;
                            if (previousCheckedValue === "1") {
                                newWhitelistCount = currentWhitelistCount - 1;
                            } else if(previousCheckedValue === "3") {
                                newBlacklistCount = currentBlacklistCount - 1;
                            }
                        }

                        $(element).attr('whitelist', newWhitelistCount);
                        $(element).attr('blacklist', newBlacklistCount);
                        $(element).attr('no-show', newNoShowCount);
                    }
                });
            }

            // the checkboxes that have the ability to hide things
            let filterCheckboxes = document.getElementsByClassName("filter-checkbox");

            // add a listener to each checkbox
            for (const element of filterCheckboxes) {
                element.addEventListener("change", function() {
                    // the classes that exist on the checkbox that is clicked
                    let checkboxClasses = this.classList;

                    // the name of the class that should be hidden
                    let toggleClass;

                    for (const element of checkboxClasses) {
                        if (element === "filter-checkbox" ||
                        element === "compact-show" ||
                        element === "compact-hide" ||
                        element.endsWith("filter-section")) {
                            continue;
                        }

                        toggleClass = element;
                    }

                    // these are the objects on the page which match the class to toggle (discluding the input boxes)
                    let matchingObjects = $("." + toggleClass).not("input");

                    // go through each of the matching objects, and modify the hide count according to the value of the checkbox
                    for (const element of matchingObjects) {
                        let currentBlacklistCount = parseInt($(element).attr('blacklist')) || 0;

                        let newBlacklistCount;
                        if ($(this).is(":checked")) {
                            newBlacklistCount = currentBlacklistCount - 1;
                        } else {
                            newBlacklistCount = currentBlacklistCount + 1;
                        }

                        $(element).attr('blacklist', newBlacklistCount);
                    }
                });
            }

            // the checkboxes that have the ability to REALLY hide things
            let noShowCheckboxes = document.getElementsByClassName("no-show-checkbox");

            // add a listener to each checkbox
            for (const element of noShowCheckboxes) {
                element.addEventListener("change", function() {
                    // the classes that exist on the checkbox that is clicked
                    let checkboxClasses = this.classList;

                    // the name of the class that should be hidden
                    let toggleClass;

                    for (const element of checkboxClasses) {
                        if (element === "no-show-checkbox" || element.endsWith("filter-section")) {
                            continue;
                        }

                        toggleClass = element;
                    }

                    // these are the objects on the page which match the class to toggle (discluding the input boxes)
                    let matchingObjects = $("." + toggleClass).not("input");

                    // go through each of the matching objects, and modify the hide count according to the value of the checkbox
                    for (const element of matchingObjects) {
                        let currentNoShowCount = parseInt($(element).attr('no-show')) || 0;

                        let newNoShowCount;
                        if ($(this).is(":checked")) {
                            newNoShowCount = currentNoShowCount - 1;
                        } else {
                            newNoShowCount = currentNoShowCount + 1;
                        }

                        $(element).attr('no-show', newNoShowCount);
                    }
                });
            }
            // the checkboxes that have the ability to "select" a line
            let selectCheckboxes = document.getElementsByClassName("select-checkbox");
            // add a listener to each checkbox
            for (const element of selectCheckboxes) {
                element.addEventListener("change", function() {
                    let potentialTopLevelLine = $(this).parent();
                    // step up parents, until you find the main log line
                    while ($(potentialTopLevelLine).parent().hasClass("log-line")) {
                        potentialTopLevelLine = $(potentialTopLevelLine).parent();
                    }
                    if ($(this).is(":checked")) {
                        $(potentialTopLevelLine).attr('selected', true);
                    } else {
                        $(potentialTopLevelLine).attr('selected', false);
                    }
                });
            }

            let selectManyButtons = document.getElementsByClassName("select-many-button");
            for (const selectManyButton of selectManyButtons) {
                // get the other class name of the button
                let selectManyButtonClasses = selectManyButton.classList;

                // the name of the section
                let sectionClass;

                for (const buttonClass of selectManyButtonClasses) {
                    if (buttonClass === "select-many-button") {
                        continue;
                    }

                    sectionClass = buttonClass;
                }

                let sectionButtons = document.getElementsByClassName(sectionClass);

                selectManyButton.addEventListener("click", function() {
                    for (const button of sectionButtons) {
                        if ($(button).hasClass("select-many-button")) {
                            continue;
                        }
                        if (!$(button).is(":checked")) {
                            button.click()
                        }
                    }
                });
            }

            let deselectManyButtons = document.getElementsByClassName("deselect-many-button");
            for (const deselectManyButton of deselectManyButtons) {
                // get the other class name of the button
                let deselectManyButtonClasses = deselectManyButton.classList;

                // the name of the section
                let sectionClass;

                for (const buttonClass of deselectManyButtonClasses) {
                    if (buttonClass === "deselect-many-button") {
                        continue;
                    }

                    sectionClass = buttonClass;
                }

                let sectionButtons = document.getElementsByClassName(sectionClass);

                deselectManyButton.addEventListener("click", function() {
                    for (const button of sectionButtons) {
                        if ($(button).hasClass("deselect-many-button")) {
                            continue;
                        }
                        if ($(button).is(":checked")) {
                            button.click()
                        }
                    }
                });
            }

            let selectCompactButtons = document.getElementsByClassName("select-compact-button");
            for (const selectCompactButton of selectCompactButtons) {
                // get the other class name of the button
                let selectCompactButtonClasses = selectCompactButton.classList;

                // the name of the section
                let sectionClass;

                for (const buttonClass of selectCompactButtonClasses) {
                    if (buttonClass === "select-compact-button") {
                        continue;
                    }

                    console.log(buttonClass);
                    sectionClass = buttonClass;
                }

                let sectionButtons = document.getElementsByClassName(sectionClass);

                selectCompactButton.addEventListener("click", function() {
                    for (const button of sectionButtons) {
                        if ($(button).hasClass("select-compact-button")) {
                            continue;
                        }
                        if (!$(button).is(":checked") && $(button).hasClass("compact-show") ||
                        $(button).is(":checked") && $(button).hasClass("compact-hide")) {
                            button.click()
                        }
                    }
                });
            }

            let columnSelectButtons = document.getElementsByClassName("select-column-button");
            for (const columnSelectButton of columnSelectButtons) {
                // get the other class name of the button
                let columnSelectButtonClasses = columnSelectButton.classList;

                // the name of the section
                let sectionClass;
                let radioTypeClass;

                for (const buttonClass of columnSelectButtonClasses) {
                    if (buttonClass === "select-column-button") {
                        continue;
                    }

                    if (buttonClass.endsWith("-radio")) {
                        radioTypeClass = buttonClass;
                        continue;
                    }

                    sectionClass = buttonClass;
                }

                let sectionButtons = document.getElementsByClassName(sectionClass + " " + radioTypeClass);

                columnSelectButton.addEventListener("click", function() {
                    for (const button of sectionButtons) {
                        if ($(button).hasClass("select-column-button")) {
                            continue;
                        }
                        button.click()
                    }
                });
            }

            // set the compact view automatically
            let compactHidden = document.getElementsByClassName("compact-hide");
            for (const element of compactHidden) {
                element.click();
            }
            """;

    /**
     * Hidden constructor.
     */
    private HtmlGenerator() {}

    /**
     * Create show / hide checkboxes for node IDs
     *
     * @param sectionName the name of the filter section this checkbox is in
     * @param nodeId      the node ID
     * @return the radio buttons
     */
    @NonNull
    private static String createNodeIdCheckbox(@NonNull final String sectionName, @NonNull final String nodeId) {
        // label used for filtering by node ID
        final String nodeLogicLabel = "node" + nodeId;
        // label used for colorizing by node ID
        final String nodeStylingLabel = "node-" + nodeId;

        final StringBuilder stringBuilder = new StringBuilder();

        stringBuilder
                .append(new HtmlTagFactory("input")
                        .addClasses(List.of(NO_SHOW_CHECKBOX_LABEL, nodeLogicLabel, sectionName))
                        .addAttribute("type", "checkbox")
                        .addAttribute("checked", "checked")
                        .generateTag())
                .append("\n");

        stringBuilder
                .append(new HtmlTagFactory("label", nodeLogicLabel)
                        .addClass(nodeStylingLabel)
                        .generateTag())
                .append("\n");
        stringBuilder.append(new HtmlTagFactory("br").generateTag()).append("\n");

        return stringBuilder.toString();
    }

    /**
     * Create a single checkbox filter
     *
     * @param elementName the name of the element to hide / show
     * @param sectionName the name of the filter section this checkbox is in
     * @param compactView whether or not the checkbox should be checked in compact view
     * @return the checkbox
     */
    @NonNull
    private static String createCheckboxFilter(
            @NonNull final String elementName, @NonNull final String sectionName, final boolean compactView) {

        final HtmlTagFactory tagFactory = new HtmlTagFactory("input")
                .addClasses(List.of(FILTER_CHECKBOX_LABEL, elementName, sectionName))
                .addAttribute("type", "checkbox")
                .addAttribute("checked", "checked");

        if (compactView) {
            tagFactory.addClass("compact-show");
        } else {
            tagFactory.addClass("compact-hide");
        }

        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(tagFactory.generateTag()).append("\n");

        stringBuilder
                .append(new HtmlTagFactory("label", elementName).generateTag())
                .append("\n");
        stringBuilder.append(new HtmlTagFactory("br").generateTag()).append("\n");

        return stringBuilder.toString();
    }

    /**
     * Create a set of radio buttons that can hide elements with the given name
     * <p>
     * This method creates 3 radio buttons, whitelist, blacklist, and neutral
     *
     * @param sectionName the name of the filter section this radio button is in
     * @param elementName the name of the element to hide
     * @return the radio filter group
     */
    @NonNull
    private static String createStandardRadioFilterWithoutLabelClass(
            @NonNull final String sectionName, @NonNull final String elementName) {
        return createStandardRadioFilter(sectionName, elementName, null);
    }

    /**
     * Create a set of radio buttons that can hide elements with the given name
     * <p>
     * This method creates 3 radio buttons, whitelist, blacklist, and neutral
     *
     * @param sectionName the name of the filter section this radio button is in
     * @param elementName the name of the element to hide
     * @param labelClass  the class to apply to the label, for styling
     * @return the radio filter group
     */
    @NonNull
    private static String createStandardRadioFilter(
            @NonNull final String sectionName, @NonNull final String elementName, @Nullable String labelClass) {
        final String commonRadioLabel = elementName + "-radio";

        final StringBuilder stringBuilder = new StringBuilder();

        stringBuilder
                .append(new HtmlTagFactory("input")
                        .addClasses(List.of(
                                FILTER_RADIO_LABEL, WHITELIST_LABEL, WHITELIST_RADIO_LABEL, elementName, sectionName))
                        .addAttribute("type", "radio")
                        .addAttribute("name", commonRadioLabel)
                        .addAttribute("value", "1")
                        .generateTag())
                .append("\n");
        stringBuilder
                .append(new HtmlTagFactory("input")
                        .addClasses(List.of(FILTER_RADIO_LABEL, NEUTRALLIST_RADIO_LABEL, elementName, sectionName))
                        .addAttribute("type", "radio")
                        .addAttribute("name", commonRadioLabel)
                        .addAttribute("checked", "checked")
                        .addAttribute("value", "2")
                        .generateTag())
                .append("\n");
        stringBuilder
                .append(new HtmlTagFactory("input")
                        .addClasses(List.of(
                                FILTER_RADIO_LABEL, BLACKLIST_LABEL, BLACKLIST_RADIO_LABEL, elementName, sectionName))
                        .addAttribute("type", "radio")
                        .addAttribute("name", commonRadioLabel)
                        .addAttribute("value", "3")
                        .generateTag())
                .append("\n");

        final HtmlTagFactory labelTagFactory = new HtmlTagFactory("label", elementName);

        if (labelClass != null) {
            labelTagFactory.addClass(labelClass);
        }

        stringBuilder.append(labelTagFactory.generateTag()).append("\n");
        stringBuilder.append(new HtmlTagFactory("br").generateTag()).append("\n");

        return stringBuilder.toString();
    }

    /**
     * Wraps a filter heading and series of filter input buttons in a form, then a div
     *
     * @param heading      the heading for the filter
     * @param bodyElements the filter input buttons
     * @return the div
     */
    @NonNull
    private static String createInputDiv(@NonNull final String heading, @NonNull final List<String> bodyElements) {
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(new HtmlTagFactory("h3", heading).generateTag()).append("\n");

        stringBuilder
                .append(new HtmlTagFactory("form", "\n" + String.join("\n", bodyElements))
                        .addAttribute("autocomplete", "off")
                        .generateTag())
                .append("\n");

        return new HtmlTagFactory("div", stringBuilder.toString()).generateTag();
    }

    /**
     * Create the div for node ID filters
     *
     * @param filterValues the different node IDs to make filters for
     * @return the node ID filter div
     */
    @NonNull
    private static String createNodeIdFilterDiv(@NonNull final List<String> filterValues) {
        final List<String> elements = new ArrayList<>();

        final String sectionName = "node-filter-section";
        elements.add(new HtmlTagFactory("input")
                .addClass(sectionName)
                .addClass(SELECT_MANY_BUTTON_LABEL)
                .addAttribute("type", "button")
                .addAttribute("value", "All")
                .generateTag());
        elements.add(new HtmlTagFactory("input")
                .addClass(sectionName)
                .addClass(DESELECT_MANY_BUTTON_LABEL)
                .addAttribute("type", "button")
                .addAttribute("value", "None")
                .generateTag());
        elements.add(new HtmlTagFactory("br").generateTag());

        filterValues.forEach(filterValue -> elements.add(createNodeIdCheckbox(sectionName, filterValue)));

        return createInputDiv("Node ID", elements);
    }

    /**
     * Create the div for column filters.
     *
     * @param filterValues the different column names to make filters for
     * @param compactView  the compact states of the checkboxes. a value of true means shown
     * @return the column filter div
     */
    @NonNull
    private static String createColumnFilterDiv(
            @NonNull final List<String> filterValues, @NonNull final List<Boolean> compactView) {

        final String sectionName = "column-filter-section";

        final List<String> elements = new ArrayList<>();
        elements.add(new HtmlTagFactory("input")
                .addClass(sectionName)
                .addClass(SELECT_MANY_BUTTON_LABEL)
                .addAttribute("type", "button")
                .addAttribute("value", "All")
                .generateTag());
        elements.add(new HtmlTagFactory("input")
                .addClass(sectionName)
                .addClass(SELECT_COMPACT_BUTTON_LABEL)
                .addAttribute("type", "button")
                .addAttribute("value", "Compact")
                .generateTag());
        elements.add(new HtmlTagFactory("br").generateTag());

        for (int i = 0; i < filterValues.size(); i++) {
            elements.add(createCheckboxFilter(filterValues.get(i), sectionName, compactView.get(i)));
        }

        return createInputDiv("Columns", elements);
    }

    @NonNull
    private static String createRadioColumnSelectorButtons(@NonNull final String sectionName) {
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(new HtmlTagFactory("input")
                .addClass(SECLECT_COLUMN_BUTTON_LABEL)
                .addClass(WHITELIST_RADIO_LABEL)
                .addClass(sectionName)
                .addAttribute("type", "button")
                .addAttribute("value", "v")
                .generateTag());
        stringBuilder.append(new HtmlTagFactory("input")
                .addClass(SECLECT_COLUMN_BUTTON_LABEL)
                .addClass(NEUTRALLIST_RADIO_LABEL)
                .addClass(sectionName)
                .addAttribute("type", "button")
                .addAttribute("value", "v")
                .generateTag());
        stringBuilder.append(new HtmlTagFactory("input")
                .addClass(SECLECT_COLUMN_BUTTON_LABEL)
                .addClass(BLACKLIST_RADIO_LABEL)
                .addClass(sectionName)
                .addAttribute("type", "button")
                .addAttribute("value", "v")
                .generateTag());
        stringBuilder.append(new HtmlTagFactory("br").generateTag()).append("\n");

        return stringBuilder.toString();
    }

    /**
     * Create a standard 3 radio filter div for the given filter name and values
     * <p>
     * The filter div has a heading, and a series of radio buttons that can hide elements with the given names
     *
     * @param sectionName  the name of the filter section
     * @param filterName   the filter name
     * @param filterValues the filter values
     * @return the filter div
     */
    @NonNull
    private static String createStandardFilterDivWithoutLabelClasses(
            @NonNull final String sectionName,
            @NonNull final String filterName,
            @NonNull final List<String> filterValues) {

        final List<String> elements = new ArrayList<>();
        elements.add(createRadioColumnSelectorButtons(sectionName));

        filterValues.forEach(
                filterValue -> elements.add(createStandardRadioFilterWithoutLabelClass(sectionName, filterValue)));
        ;
        return createInputDiv(filterName, elements);
    }

    /**
     * Create a standard 3 radio filter div for the given filter name and values
     * <p>
     * The filter div has a heading, and a series of radio buttons that can hide elements with the given names
     * <p>
     * The radio labels will have the classes defined in labelClasses.
     *
     * @param sectionName  the name of the filter section
     * @param filterName   the filter name
     * @param filterValues the filter values
     * @param labelClasses the classes to apply to the radio labels
     * @return the filter div
     */
    private static String createStandardFilterDivWithLabelClasses(
            @NonNull final String sectionName,
            @NonNull final String filterName,
            @NonNull final List<String> filterValues,
            @NonNull final Map<String, String> labelClasses) {

        final List<String> elements = new ArrayList<>();
        elements.add(createRadioColumnSelectorButtons(sectionName));

        filterValues.forEach(filterValue ->
                elements.add(createStandardRadioFilter(sectionName, filterValue, labelClasses.get(filterValue))));
        return createInputDiv(filterName, elements);
    }

    /**
     * Create CSS rules that apply to the whole HTML page
     * <p>
     * Rules specific to individual elements should be added where those elements are being constructed
     *
     * @param logLines   the log lines
     * @param cssFactory a factory that new rules can be added to
     */
    private static void createGeneralCssRules(
            @NonNull final List<LogLine> logLines, @NonNull final CssRuleSetFactory cssFactory) {

        // set page defaults
        cssFactory.addRule(
                "html *",
                new CssDeclaration("font-family", DEFAULT_FONT),
                new CssDeclaration("background-color", PAGE_BACKGROUND_COLOR),
                new CssDeclaration("color", DEFAULT_TEXT_COLOR),
                new CssDeclaration("white-space", "nowrap"),
                new CssDeclaration("vertical-align", "top"));

        // hide elements that have a blacklist value, as long as they don't have a whitelist value, and aren't selected
        cssFactory.addRule(
                "[%s]:not([%s~='0']):not([%s~=\"NaN\"]):is([%s='0']):not([selected])"
                        .formatted(BLACKLIST_LABEL, BLACKLIST_LABEL, BLACKLIST_LABEL, WHITELIST_LABEL),
                new CssDeclaration("display", "none"));

        // strongly hide any elements with a no-show value of > 1. Only selected elements are exempt
        cssFactory.addRule(
                "[%s]:not([%s~='0']):not([selected])".formatted(NO_SHOW, NO_SHOW),
                new CssDeclaration("display", "none !important"));

        // pad the log table columns
        cssFactory.addRule("td", new CssDeclaration("padding-left", "1em"));

        // set a max width for remainder column, and wrap words
        cssFactory.addRule(
                "." + REMAINDER_OF_LINE_COLUMN_LABEL,
                new CssDeclaration("max-width", "100em"),
                new CssDeclaration("overflow-wrap", "break-word"),
                new CssDeclaration("word-break", "break-word"),
                new CssDeclaration("white-space", "normal"));

        // wrap non standard log lines
        cssFactory.addRule(
                "." + NON_STANDARD_LABEL,
                new CssDeclaration("white-space", "pre-wrap"),
                new CssDeclaration("word-break", "break-word"),
                new CssDeclaration("overflow-wrap", "break-word"));

        // set a max width for thread name column, and wrap words
        cssFactory.addRule(
                "." + THREAD_NAME_COLUMN_LABEL,
                new CssDeclaration("max-width", "30em"),
                new CssDeclaration("overflow-wrap", "break-word"),
                new CssDeclaration("word-break", "break-word"),
                new CssDeclaration("white-space", "normal"));

        cssFactory.addRule("." + NODE_ID_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(TIMESTAMP_COLOR)));
        cssFactory.addRule("." + ELAPSED_TIME_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(TIMESTAMP_COLOR)));
        cssFactory.addRule("." + TIMESTAMP_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(TIMESTAMP_COLOR)));
        cssFactory.addRule("." + LOG_NUMBER_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(LOG_NUMBER_COLOR)));
        cssFactory.addRule("." + MARKER_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(LOG_MARKER_COLOR)));
        cssFactory.addRule(
                "." + THREAD_NAME_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(THREAD_NAME_COLOR)));
        cssFactory.addRule("." + CLASS_NAME_COLUMN_LABEL, new CssDeclaration("color", getHtmlColor(CLASS_NAME_COLOR)));
        cssFactory.addRule(
                "." + STATUS_HTML_CLASS,
                new CssDeclaration("color", getHtmlColor(PlatformStatusLog.STATUS_COLOR)),
                new CssDeclaration("background-color", "inherit"));

        // highlight log lines when you hover over them with your mouse
        cssFactory.addRule("." + LOG_LINE_LABEL + ":hover td", new CssDeclaration("background-color", HIGHLIGHT_COLOR));

        cssFactory.addRule(
                "." + SELECT_MANY_BUTTON_LABEL + ", ." + DESELECT_MANY_BUTTON_LABEL + ", ."
                        + SELECT_COMPACT_BUTTON_LABEL,
                new CssDeclaration("background-color", getHtmlColor(TextEffect.GRAY)));

        // make the select column buttons the same color as the accent of the radio buttons=
        cssFactory.addRule(
                "." + SECLECT_COLUMN_BUTTON_LABEL + "." + WHITELIST_RADIO_LABEL,
                new CssDeclaration("border-color", WHITELIST_RADIO_COLOR));
        cssFactory.addRule(
                "." + SECLECT_COLUMN_BUTTON_LABEL + "." + NEUTRALLIST_RADIO_LABEL,
                new CssDeclaration("border-color", NEUTRALLIST_RADIO_COLOR));
        cssFactory.addRule(
                "." + SECLECT_COLUMN_BUTTON_LABEL + "." + BLACKLIST_RADIO_LABEL,
                new CssDeclaration("border-color", BLACKLIST_RADIO_COLOR));

        cssFactory.addRule(
                "." + SECLECT_COLUMN_BUTTON_LABEL,
                new CssDeclaration("margin", "1px"),
                new CssDeclaration("width", "2em"));

        // create color rules for each log level
        logLines.stream()
                .map(LogLine::getLogLevel)
                .distinct()
                .forEach(logLevel -> cssFactory.addRule(
                        "td" + "." + logLevel + "-level",
                        new CssDeclaration("color", getHtmlColor(getLogLevelColor(logLevel)))));

        // create color rules for each node ID
        logLines.stream()
                .map(LogLine::getNodeId)
                .distinct()
                .filter(Objects::nonNull)
                .forEach(nodeId -> {
                    final String color = NodeIdColorizer.getNodeIdColor(nodeId);

                    cssFactory.addRule(
                            "td.node-" + nodeId + ", label.node-" + nodeId,
                            new CssDeclaration("color", color == null ? DEFAULT_TEXT_COLOR : color));
                });

        // add transparent borders that match node id color
        IntStream.range(0, NodeIdColorizer.nodeIdColors.size())
                .forEach(index -> cssFactory.addRule(
                        "tr[selected].node" + index + ", tbody[selected].node" + index,
                        new CssDeclaration("outline", "2px solid " + NodeIdColorizer.nodeIdColors.get(index) + "50"),
                        new CssDeclaration("outline-offset", "-2px")));
    }

    /**
     * Generate the head of the HTML page
     *
     * @param cssFactory the css rule factory
     * @return the head of the HTML page
     */
    @NonNull
    private static String generateHead(@NonNull final CssRuleSetFactory cssFactory) {
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder
                .append(new HtmlTagFactory("style", cssFactory.generateCss()).generateTag())
                .append("\n");

        stringBuilder
                .append(new HtmlTagFactory("script", "")
                        .addAttribute("src", MIN_JS_SOURCE)
                        .generateTag())
                .append("\n");

        return new HtmlTagFactory("head", stringBuilder.toString()).generateTag();
    }

    /**
     * Generate the generate filters div for the html page
     *
     * @param logLines   the log lines
     * @param cssFactory a factory that new rules can be added to
     * @return the generate filters div for the html page
     */
    @NonNull
    private static String generateFiltersDiv(
            @NonNull final List<LogLine> logLines, @NonNull final CssRuleSetFactory cssFactory) {

        final StringBuilder filterDivBuilder = new StringBuilder();

        filterDivBuilder.append(createNodeIdFilterDiv(logLines.stream()
                .map(LogLine::getNodeId)
                .distinct()
                .filter(Objects::nonNull)
                .sorted()
                .map(NodeId::toString)
                .toList()));

        filterDivBuilder.append(createColumnFilterDiv(
                List.of(
                        NODE_ID_COLUMN_LABEL,
                        ELAPSED_TIME_COLUMN_LABEL,
                        TIMESTAMP_COLUMN_LABEL,
                        LOG_NUMBER_COLUMN_LABEL,
                        LOG_LEVEL_COLUMN_LABEL,
                        MARKER_COLUMN_LABEL,
                        THREAD_NAME_COLUMN_LABEL,
                        CLASS_NAME_COLUMN_LABEL,
                        REMAINDER_OF_LINE_COLUMN_LABEL),
                List.of(true, true, false, false, true, false, false, true, true)));

        logLevelLabels.forEach((logLevel, labelClass) -> cssFactory.addRule(
                "." + labelClass, new CssDeclaration("color", getHtmlColor(getLogLevelColor(logLevel)))));
        filterDivBuilder
                .append(createStandardFilterDivWithLabelClasses(
                        "log-level-filter-section",
                        "Log Level",
                        logLines.stream()
                                .map(LogLine::getLogLevel)
                                .distinct()
                                .sorted(Comparator.comparing(Level::toLevel))
                                .toList(),
                        logLevelLabels))
                .append("\n");

        filterDivBuilder
                .append(createStandardFilterDivWithoutLabelClasses(
                        "log-marker-filter-section",
                        "Log Marker",
                        logLines.stream().map(LogLine::getMarker).distinct().toList()))
                .append("\n");

        filterDivBuilder
                .append(createStandardFilterDivWithoutLabelClasses(
                        "class-filter-section",
                        "Class",
                        logLines.stream().map(LogLine::getClassName).distinct().toList()))
                .append("\n");

        final StringBuilder containingDivBuilder = new StringBuilder();
        containingDivBuilder
                .append(new HtmlTagFactory("div", filterDivBuilder.toString())
                        .addClass(INDEPENDENT_SCROLL_LABEL)
                        .generateTag())
                .append("\n");

        cssFactory.addRule("." + WHITELIST_RADIO_LABEL, new CssDeclaration("accent-color", WHITELIST_RADIO_COLOR));
        cssFactory.addRule("." + NEUTRALLIST_RADIO_LABEL, new CssDeclaration("accent-color", NEUTRALLIST_RADIO_COLOR));
        cssFactory.addRule("." + BLACKLIST_RADIO_LABEL, new CssDeclaration("accent-color", BLACKLIST_RADIO_COLOR));

        // make the filter columns and the log table scroll independently
        cssFactory.addRule("." + INDEPENDENT_SCROLL_LABEL, new CssDeclaration("overflow", "auto"));
        cssFactory.addRule("." + INDEPENDENT_SCROLL_LABEL, new CssDeclaration("height", "99vh"));

        return new HtmlTagFactory("div", containingDivBuilder.toString()).generateTag();
    }

    /**
     * Generate the log table for the HTML page
     *
     * @param logLines   the log lines
     * @param cssFactory a factory that new rules can be added to
     * @return the log table for the HTML page
     */
    @NonNull
    private static String generateLogTable(
            @NonNull final List<LogLine> logLines, @NonNull final CssRuleSetFactory cssFactory) {
        final StringBuilder stringBuilder = new StringBuilder().append("\n");

        logLines.stream()
                .map(LogLine::generateHtmlString)
                .forEach(logHtml -> stringBuilder.append(logHtml).append("\n"));

        cssFactory.addRule("." + LOG_TABLE_LABEL, new CssDeclaration("border-collapse", "collapse"));

        return new HtmlTagFactory("table", stringBuilder.toString())
                .addClass(LOG_TABLE_LABEL)
                .generateTag();
    }

    /**
     * Generate the body of the HTML page
     *
     * @param logLines   the log lines
     * @param cssFactory a factory that new rules can be added to
     * @return the body of the HTML page
     */
    @NonNull
    private static String generateBody(
            @NonNull final List<LogLine> logLines, @NonNull final CssRuleSetFactory cssFactory) {

        final StringBuilder doubleColumnDivBuilder = new StringBuilder();
        doubleColumnDivBuilder.append(generateFiltersDiv(logLines, cssFactory)).append("\n");
        doubleColumnDivBuilder
                .append(new HtmlTagFactory("div", generateLogTable(logLines, cssFactory))
                        .addClass(INDEPENDENT_SCROLL_LABEL)
                        .addClass(TABLE_INDEPENDENT_SCROLL_LABEL)
                        .generateTag())
                .append("\n");

        // make the log table independent scroll fill 100% of width
        cssFactory.addRule("." + TABLE_INDEPENDENT_SCROLL_LABEL, new CssDeclaration("width", "100%"));

        final StringBuilder bodyBuilder = new StringBuilder();

        // this is a div surrounding the filters and the log table
        // its purpose is so that there can be 2 independently scrollable columns
        bodyBuilder
                .append(new HtmlTagFactory("div", doubleColumnDivBuilder.toString())
                        .addClass(DOUBLE_COLUMNS_DIV_LABEL)
                        .generateTag())
                .append("\n");

        cssFactory.addRule(
                "." + DOUBLE_COLUMNS_DIV_LABEL,
                new CssDeclaration("display", "flex"),
                new CssDeclaration("height", "100%"));

        bodyBuilder
                .append(new HtmlTagFactory("script", FILTER_JS).generateTag())
                .append("\n");

        return new HtmlTagFactory("body", bodyBuilder.toString()).generateTag();
    }

    /**
     * Go through all log lines, find the earliest time, and set the log start time for each log line
     *
     * @param logLines the log lines
     */
    private static void setFirstLogTime(@NonNull final List<LogLine> logLines) {
        final LogLine firstLogLine = logLines.stream()
                .min(Comparator.comparing(LogLine::getTimestamp))
                .orElse(null);

        final Instant firstLogTime = firstLogLine == null ? null : firstLogLine.getTimestamp();

        if (firstLogTime != null) {
            logLines.forEach(logLine -> logLine.setLogStartTime(firstLogTime));
        }
    }

    /**
     * Goes through the raw log line strings from a given node, and returns the list of log lines
     *
     * @param nodeId         the node id
     * @param logLineStrings the raw log line strings
     * @return the list of log lines, which represent the raw log strings
     */
    @NonNull
    private static List<LogLine> processNodeLogLines(
            @NonNull final NodeId nodeId, @NonNull final List<String> logLineStrings) {
        final List<LogLine> outputLines = new ArrayList<>();

        LogLine previousLogLine = null;
        for (final String logLineString : logLineStrings) {
            if (logLineString == null) {
                continue;
            }

            try {
                previousLogLine = new LogLine(logLineString, ZoneId.systemDefault(), nodeId);
                outputLines.add(previousLogLine);
            } catch (final Exception e) {
                // everything in front of the first standard log line is discarded
                if (previousLogLine != null) {
                    previousLogLine.addNonStandardLine(logLineString);
                }
            }
        }
        return outputLines;
    }

    /**
     * Generate an HTML page from a list of log line strings
     *
     * @param logLineStrings a map of node id to the raw log line strings for that node
     * @return the HTML page
     */
    @NonNull
    public static String generateHtmlPage(@NonNull final Map<NodeId, List<String>> logLineStrings) {
        Objects.requireNonNull(logLineStrings);

        final List<LogLine> logLines = logLineStrings.entrySet().stream()
                .flatMap(entry -> processNodeLogLines(entry.getKey(), entry.getValue()).stream())
                .sorted(Comparator.comparing(LogLine::getTimestamp))
                .toList();

        setFirstLogTime(logLines);

        final CssRuleSetFactory cssFactory = new CssRuleSetFactory();

        createGeneralCssRules(logLines, cssFactory);

        final String body = generateBody(logLines, cssFactory);

        final StringBuilder htmlBuilder = new StringBuilder().append("\n");

        // head must be generated after the body, since that's where we turn the CSS rules into a string to put into the
        // HTML doc
        htmlBuilder.append(generateHead(cssFactory)).append("\n");
        htmlBuilder.append(body).append("\n");

        return new HtmlTagFactory("html", htmlBuilder.toString()).generateTag();
    }
}
