// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.testreader;

import static com.swirlds.common.units.TimeUnit.UNIT_MILLISECONDS;
import static com.swirlds.platform.testreader.TestStatus.FAIL;
import static com.swirlds.platform.testreader.TestStatus.PASS;

import com.swirlds.common.formatting.UnitFormatter;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates HTML JRS Test Reports.
 */
public final class JrsTestReportGenerator {

    private JrsTestReportGenerator() {}

    /**
     * The test url stored in this test result is a gs:// url. This method generates a url that can be visited in a web
     * browser.
     *
     * @return a url that can be visited in a web browser
     */
    private static String generateWebBrowserUrl(
            @NonNull final String testDirectory,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement) {
        return testDirectory.replace(bucketPrefix, bucketPrefixReplacement);
    }

    private static void generateHyperlink(
            @NonNull final StringBuilder sb, @NonNull final String text, @NonNull final String url) {

        sb.append("<a target=\"_blank\" href=\"%s\">%s</a>".formatted(url, text));
    }

    private static void generateColoredHyperlink(
            @NonNull final StringBuilder sb,
            @NonNull final String text,
            @NonNull final String url,
            @NonNull final String color) {

        sb.append("<a target=\"_blank\" style=\"color: %s\" href=\"%s\">%s</a>".formatted(color, url, text));
    }

    private static void generateTitle(@NonNull final StringBuilder sb, @NonNull final Instant now) {
        sb.append("<title>").append("JRS Test Report: ").append(now).append("</title>\n");
    }

    private static void generatePageStyle(@NonNull final StringBuilder sb) {
        final LocalDate currentDate = LocalDate.now();
        final boolean april2 = currentDate.getMonth().equals(Month.APRIL) && currentDate.getDayOfMonth() == 2;
        final String font;
        if (april2) {
            font = "Snell Roundhand, cursive";
        } else {
            font = "Jetbrains Mono, monospace";
        }

        sb.append(
                """
                        <style>
                            body {
                                font-family: %s;
                                font-size: 14px;
                                color: #bdbfc4;
                                background-color: #1e1e23;
                            }
                            .testDataTable > tbody > tr > th {
                                position: sticky;
                                top: 0;
                                border: 2px #AAAAAA solid;
                                padding: 3px;
                                background-color: #1e1e23;
                                font-size: 16px;
                                color: #DDDDDD;
                            }
                            .testDataTable > tbody > tr > td {
                                border: 1px #555555 solid;
                                padding: 10px;
                            }
                            .testDataTable > tbody > tr:hover td {
                                border: 3px solid gray;
                                padding: 8px;
                            }
                            .testDataTable > tbody > tr > td:hover {
                                border-color: #DDDDDD;
                            }
                            .sidePanel {
                                position: sticky;
                                top: 12px;
                            }
                            .topLevelTable > tbody > tr > td {
                                border: none;
                            }
                            .overview > tbody > tr > td {
                                border: none;
                                padding-left: 5px;
                                padding-right: 5px;
                                vertical-align: top;
                            }
                            .statusCell {
                                color: black;
                            }
                            .important {
                                color: #aea85d;
                            }
                            a:link {
                                color: #538af7;
                            }
                            a:visited {
                                color: #a64dff;
                            }
                            a:hover {
                                color: #aea85d;
                            }
                            a:link.passLink {
                                color: #3cb371;
                            }
                            a:visited.passLink {
                                color: #267349;
                            }
                            a:hover.passLink {
                                color: #aea85d;
                            }
                            a:link.failLink {
                                color: #f0524f;
                            }
                            a:visited.failLink {
                                color: #d31612;
                            }
                            a:hover.failLink {
                                color: #aea85d;
                            }
                            a:link.unknownLink {
                                color: slateBlue;
                            }
                            a:hover.unknownLink {
                                color: slateBlue;
                            }
                            .leftColumn {
                                vertical-align: top;
                                horizontal-align: right;
                                width: 75%%
                            }
                            .rightColumn {
                                vertical-align: top;
                                horizontal-align: left;
                                width: 25%%
                            }
                        </style>
                        """
                        .formatted(font));
    }

    private static void generateJavascript(@NonNull final StringBuilder sb) {
        sb.append(
                """
                        <script>
                            function onLoad() {
                                registerClickListeners();
                                colorAllTableRows();
                            }
                            function registerClickListeners() {
                                registerClickListenersForTable('table_sortedByName');
                                registerClickListenersForTable('table_sortedByAge');
                                registerClickListenersForTable('table_sortedByStatus');
                            }
                            var previouslySelectedRow = null;
                            function registerClickListenersForTable(tableName) {
                                var table = document.getElementById(tableName);
                                for (var i = 1; i < table.rows.length; i++) {
                                    var row = table.rows[i];
                                    for (var j = 0; j < row.cells.length; j++) {
                                        var cell = row.cells[j];
                                        cell.onclick = (function() {
                                            if (previouslySelectedRow == this) {
                                                return;
                                            }
                                            for (var k = 0; k < this.cells.length; k++) {
                                                this.cells[k].style.borderBottomColor = "#f0524f";
                                            }
                                            if (previouslySelectedRow != null) {
                                                for (var k = 0; k < previouslySelectedRow.cells.length; k++) {
                                                    previouslySelectedRow.cells[k].style.borderBottomColor = "";
                                                }
                                            }
                                            previouslySelectedRow = this;
                                        }).bind(row);
                                    }
                                }
                            }
                            function sortByName() {
                                document.getElementById('table_sortedByName').style.display = "block";
                                document.getElementById('table_sortedByAge').style.display = "none";
                                document.getElementById('table_sortedByStatus').style.display = "none";
                            }
                            function sortByAge() {
                                document.getElementById('table_sortedByName').style.display = "none";
                                document.getElementById('table_sortedByAge').style.display = "block";
                                document.getElementById('table_sortedByStatus').style.display = "none";
                            }
                            function sortByStatus() {
                                document.getElementById('table_sortedByName').style.display = "none";
                                document.getElementById('table_sortedByAge').style.display = "none";
                                document.getElementById('table_sortedByStatus').style.display = "block";
                            }
                            function showOverviewForOwner(owner) {
                                var overviews = document.getElementsByClassName('overview');
                                var desiredOverview = "overview_" + owner;
                                for (var i = 0; i < overviews.length; i++) {
                                    if (overviews[i].id == desiredOverview) {
                                        overviews[i].style.display = "block";
                                    } else {
                                        overviews[i].style.display = "none";
                                    }
                                }
                            }
                            function showInTableIfOwnedBy(owner, tableId) {
                                var table = document.getElementById(tableId);
                                for (var i = 1, row; row = table.rows[i]; i++) {
                                    // owner is in column 2
                                    var rowOwner = row.cells[2].innerHTML;
                                    if (rowOwner == owner) {
                                        row.style.display = "table-row";
                                    } else {
                                        row.style.display = "none";
                                    }
                                }
                            }
                            function showIfOwnedBy(owner) {
                                showInTableIfOwnedBy(owner, 'table_sortedByName');
                                showInTableIfOwnedBy(owner, 'table_sortedByAge');
                                showInTableIfOwnedBy(owner, 'table_sortedByStatus');
                            }
                            function showAllRowsInTable(tableId) {
                                var table = document.getElementById(tableId);
                                for (var i = 1, row; row = table.rows[i]; i++) {
                                    row.style.display = "table-row";
                                }
                            }
                            function showAllRows() {
                                showAllRowsInTable('table_sortedByName');
                                showAllRowsInTable('table_sortedByAge');
                                showAllRowsInTable('table_sortedByStatus');
                            }
                            function switchToOwnerView(owner) {
                                if (owner == "all") {
                                    showAllRows();
                                    showOverviewForOwner("all");
                                } else if (owner == "") {
                                    showIfOwnedBy("");
                                    showOverviewForOwner("unassigned");
                                } else {
                                    showIfOwnedBy(owner);
                                    showOverviewForOwner(owner);
                                }
                                colorAllTableRows();
                            }
                            function colorTableRows(tableId) {
                                var table = document.getElementById(tableId);
                                var light = true;
                                for (var i = 1, row; row = table.rows[i]; i++) {
                                    if (row.style.display == "none") {
                                        continue;
                                    }
                                    if (light) {
                                        row.style.backgroundColor = "#2f2f37";
                                    } else {
                                        row.style.backgroundColor = "#1e1e23";
                                    }
                                    light = !light;
                                }
                            }
                            function colorAllTableRows() {
                                colorTableRows('table_sortedByName');
                                colorTableRows('table_sortedByAge');
                                colorTableRows('table_sortedByStatus');
                            }
                        </script>
                        """);
    }

    private static void generateHeader(@NonNull final StringBuilder sb, @NonNull final Instant now) {
        sb.append("<head>\n");
        generateTitle(sb, now);
        generatePageStyle(sb);
        generateJavascript(sb);
        sb.append("</head>\n");
    }

    private static void generatePanelCell(@NonNull final StringBuilder sb, @NonNull final String panelName) {
        sb.append("<td>").append(panelName).append("</td>\n");
    }

    private static void generateNameCell(@NonNull final StringBuilder sb, @NonNull final String testName) {
        sb.append("""
                <td class="important">%s</td>
                """.formatted(testName));
    }

    private static void generateOwnerCell(@NonNull final StringBuilder sb, @NonNull final String owner) {
        sb.append("<td>").append(owner).append("</td>\n");
    }

    private static void generateAgeCell(
            @NonNull final StringBuilder sb, @NonNull final Instant now, @NonNull final Instant testTime) {

        final Duration testAge = Duration.between(testTime, now);
        final String ageString = new UnitFormatter(testAge.toMillis(), UNIT_MILLISECONDS)
                .setShowSpaceInBetween(false)
                .setAbbreviate(true)
                .render();

        sb.append("<td>").append(ageString).append("</td>\n");
    }

    private static void generateStatusCell(@NonNull final StringBuilder sb, @NonNull final TestStatus status) {
        final String statusColor;
        if (status == TestStatus.PASS) {
            statusColor = "#3cb371";
        } else if (status == TestStatus.FAIL) {
            statusColor = "#f0524f";
        } else {
            statusColor = "slateBlue";
        }

        sb.append("""
                <td class="statusCell" bgcolor="%s"><center>%s</center></td>
                """
                .formatted(statusColor, status.name()));
    }

    private static void generateHistoryCell(
            @NonNull final StringBuilder sb,
            @NonNull final List<JrsTestResult> historicalResults,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement) {

        sb.append("<td>");

        // Always ignore the first result since it is already reported
        for (int index = 1; index < historicalResults.size(); index++) {

            final JrsTestResult result = historicalResults.get(index);

            final String testUrl = generateWebBrowserUrl(result.testDirectory(), bucketPrefix, bucketPrefixReplacement);
            final String resultString;

            final String linkClass;
            if (result.status() == TestStatus.PASS) {
                resultString = "P";
                linkClass = "passLink";
            } else if (result.status() == TestStatus.FAIL) {
                resultString = "F";
                linkClass = "failLink";
            } else {
                resultString = "?";
                linkClass = "unknownLink";
            }

            sb.append("""
                    <a class="%s" href="%s" target="_blank">%s</a>"""
                    .formatted(linkClass, testUrl, resultString));
        }

        sb.append("</td>\n");
    }

    private static void generateSummaryCell(@NonNull final StringBuilder sb, @NonNull final String testUrl) {
        sb.append("<td>");
        generateHyperlink(sb, "summary", testUrl + "summary.txt");
        sb.append("</td>\n");
    }

    private static void generateLogsCell(@NonNull final StringBuilder sb, @NonNull final String testUrl) {
        sb.append("<td>");
        generateHyperlink(sb, "logs", testUrl + "swirlds-logs.html");
        sb.append("</td>\n");
    }

    private static void generateMetricsCell(@NonNull final StringBuilder sb, @NonNull final String testUrl) {
        sb.append("<td>");
        generateHyperlink(sb, "metrics", testUrl + "multipage_pdf.pdf");
        sb.append("</td>\n");
    }

    private static void generateDataCell(@NonNull final StringBuilder sb, @NonNull final String testUrl) {
        sb.append("<td>");
        generateHyperlink(sb, "data", testUrl);
        sb.append("</td>\n");
    }

    private static void generateNotesCell(@NonNull final StringBuilder sb, @NonNull final String notesUrl) {
        sb.append("<td>");
        if (!notesUrl.isBlank()) {
            generateHyperlink(sb, "notes", notesUrl);
        }
        sb.append("</td>\n");
    }

    private static void generateTableRow(
            @NonNull final StringBuilder sb,
            @NonNull final JrsTestReportRow row,
            @NonNull final Instant now,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement) {

        final String testUrl =
                generateWebBrowserUrl(row.getMostRecentTest().testDirectory(), bucketPrefix, bucketPrefixReplacement);

        sb.append("<tr>\n");
        generatePanelCell(sb, row.getMostRecentTest().id().panel());
        generateNameCell(sb, row.getMostRecentTest().id().name());
        generateOwnerCell(sb, row.metadata() == null ? "" : row.metadata().owner());
        generateAgeCell(sb, now, row.getMostRecentTest().timestamp());
        generateStatusCell(sb, row.getMostRecentTest().status());
        generateHistoryCell(sb, row.tests(), bucketPrefix, bucketPrefixReplacement);
        generateSummaryCell(sb, testUrl);
        generateLogsCell(sb, testUrl);
        generateMetricsCell(sb, testUrl);
        generateDataCell(sb, testUrl);
        generateNotesCell(sb, row.metadata() == null ? "" : row.metadata().notesUrl());
        sb.append("</tr>\n");
    }

    private static void generateTable(
            @NonNull final StringBuilder sb,
            @NonNull final String tableId,
            @NonNull final List<JrsTestReportRow> rows,
            @NonNull final Instant now,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement,
            @NonNull final Comparator<JrsTestReportRow> comparator,
            final boolean hidden) {

        rows.sort(comparator);

        sb.append(
                """
                        <table id="%s" style="display: %s" class="testDataTable" align="right">
                            <tr>
                                <th>Panel</th>
                                <th>Test Name</th>
                                <th>Owner</th>
                                <th>Age</th>
                                <th>Status</th>
                                <th>History</th>
                                <th>Summary</th>
                                <th>Logs</th>
                                <th>Metrics</th>
                                <th>Data</th>
                                <th>Notes</th>
                            </tr>
                         """
                        .formatted(tableId, hidden ? "none" : "block"));

        for (final JrsTestReportRow row : rows) {
            generateTableRow(sb, row, now, bucketPrefix, bucketPrefixReplacement);
        }

        sb.append("</table>\n");
    }

    /**
     * Used to sort tests by status.
     */
    private static int statusComparator(@NonNull final JrsTestReportRow a, @NonNull final JrsTestReportRow b) {

        final JrsTestResult aResult = a.getMostRecentTest();
        final JrsTestResult bResult = b.getMostRecentTest();

        if (aResult.status() != bResult.status()) {
            return Integer.compare(aResult.status().ordinal(), bResult.status().ordinal());
        }

        if (aResult.status() == PASS) {
            // For passing tests, give priority to the test with the most recent test that did not pass

            int mostRecentFailureA = Integer.MAX_VALUE;
            for (int index = 1; index < a.tests().size(); index++) {
                if (a.tests().get(index).status() != PASS) {
                    mostRecentFailureA = index;
                    break;
                }
            }

            int mostRecentFailureB = Integer.MAX_VALUE;
            for (int index = 1; index < b.tests().size(); index++) {
                if (b.tests().get(index).status() != PASS) {
                    mostRecentFailureB = index;
                    break;
                }
            }

            if (mostRecentFailureA != mostRecentFailureB) {
                return Integer.compare(mostRecentFailureA, mostRecentFailureB);
            }
        } else {
            // For failing/unknown tests, give priority to the test with the most recent passing run

            int mostRecentPassA = Integer.MAX_VALUE;
            for (int index = 1; index < a.tests().size(); index++) {
                if (a.tests().get(index).status() == PASS) {
                    mostRecentPassA = index;
                    break;
                }
            }

            int mostRecentPassB = Integer.MAX_VALUE;
            for (int index = 1; index < b.tests().size(); index++) {
                if (b.tests().get(index).status() == PASS) {
                    mostRecentPassB = index;
                    break;
                }
            }

            if (mostRecentPassA != mostRecentPassB) {
                return Integer.compare(mostRecentPassA, mostRecentPassB);
            }
        }

        // If all else fails, sort by panel & name
        return aResult.id().compareTo(bResult.id());
    }

    /**
     * Used to sort tests by age.
     */
    private static int ageComparator(@NonNull final JrsTestReportRow a, @NonNull final JrsTestReportRow b) {
        if (!a.getMostRecentTest().timestamp().equals(b.getMostRecentTest().timestamp())) {
            return b.getMostRecentTest()
                    .timestamp()
                    .compareTo(a.getMostRecentTest().timestamp());
        }
        // If timestamps are equal, sort by panel & name.
        return a.getMostRecentTest().id().compareTo(b.getMostRecentTest().id());
    }

    private static void generateDataTable(
            @NonNull final StringBuilder sb,
            @NonNull final JrsReportData data,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata,
            @NonNull final Instant now,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement) {

        final List<JrsTestReportRow> rows = JrsTestReportGenerator.buildTableRows(data.testResults(), metadata);

        // Only one table will be visible at a time, the others will be hidden.
        // I'm too lazy to write javascript to sort these, so I just sort them in java
        // and have the page display the table with the desired sort order.
        generateTable(
                sb,
                "table_sortedByName",
                rows,
                now,
                bucketPrefix,
                bucketPrefixReplacement,
                Comparator.comparing(a -> a.getMostRecentTest().id()),
                false);
        generateTable(
                sb,
                "table_sortedByAge",
                rows,
                now,
                bucketPrefix,
                bucketPrefixReplacement,
                JrsTestReportGenerator::ageComparator,
                true);
        generateTable(
                sb,
                "table_sortedByStatus",
                rows,
                now,
                bucketPrefix,
                bucketPrefixReplacement,
                JrsTestReportGenerator::statusComparator,
                true);
    }

    private record TestCount(int uniqueTests, int passingTests, int failingTests, int unknownTests) {}

    @NonNull
    private static TestCount countTestsForOwner(
            @NonNull final List<JrsTestResult> results,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata,
            @NonNull final String owner) {

        // Sort tests by unique type and discard tests without the proper owner.
        final Map<JrsTestIdentifier, List<JrsTestResult>> resultsByTestType = new HashMap<>();
        for (final JrsTestResult result : results) {
            final JrsTestIdentifier id = result.id();

            if (!owner.equals("all")) {
                final JrsTestMetadata testMetadata = metadata.get(id);
                final String ownerFromMetadata = testMetadata == null ? "" : testMetadata.owner();
                if (!owner.equals(ownerFromMetadata)) {
                    continue;
                }
            }

            final List<JrsTestResult> resultsForType = resultsByTestType.computeIfAbsent(id, k -> new ArrayList<>());
            resultsForType.add(result);
        }

        // Sort each test of the same type by timestamp.
        for (final List<JrsTestResult> tests : resultsByTestType.values()) {
            Collections.sort(tests);
        }

        final int uniqueTests = resultsByTestType.size();
        int passingTests = 0;
        int failingTests = 0;
        int unknownTests = 0;
        for (final JrsTestIdentifier testIdentifier : resultsByTestType.keySet()) {
            final List<JrsTestResult> testResults = resultsByTestType.get(testIdentifier);
            final JrsTestResult mostRecentResult = testResults.get(0);
            if (mostRecentResult.status() == PASS) {
                passingTests++;
            } else if (mostRecentResult.status() == FAIL) {
                failingTests++;
            } else {
                unknownTests++;
            }
        }

        return new TestCount(uniqueTests, passingTests, failingTests, unknownTests);
    }

    private static Map<String, TestCount> countTests(
            @NonNull final List<JrsTestResult> results,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata,
            @NonNull final List<String> owners) {

        final Map<String, TestCount> testCountMap = new HashMap<>();

        for (final String owner : owners) {
            testCountMap.put(owner, countTestsForOwner(results, metadata, owner));
        }

        if (!testCountMap.containsKey("")) {
            testCountMap.put("", countTestsForOwner(results, metadata, ""));
        }

        if (!testCountMap.containsKey("all")) {
            testCountMap.put("all", countTestsForOwner(results, metadata, "all"));
        }

        return testCountMap;
    }

    private static void generateOverview(
            @NonNull final StringBuilder sb,
            @NonNull final JrsReportData data,
            @NonNull final String owner,
            @NonNull final TestCount testCount,
            final boolean hidden) {

        final LocalDate localDate =
                data.reportTime().atZone(ZoneId.systemDefault()).toLocalDate();
        final LocalTime localTime =
                data.reportTime().atZone(ZoneId.systemDefault()).toLocalTime();
        final ZonedDateTime zonedDateTime = ZonedDateTime.of(localDate, localTime, ZoneId.systemDefault());

        final String date = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(zonedDateTime);
        final String time = DateTimeFormatter.ofLocalizedTime(FormatStyle.FULL)
                .format(zonedDateTime)
                .replace("\u202f", " ");

        final int percentPassing;
        if (testCount.uniqueTests() == 0) {
            percentPassing = -1;
        } else {
            percentPassing = (int) (100.0 * testCount.passingTests() / testCount.uniqueTests());
        }

        // Color the last element in the list red.
        final String[] directoryParts = data.directory().split("/");
        final String lastDirectory = directoryParts[directoryParts.length - 1];
        final String formattedLastDirectory = "<font color=\"#f0524f\">" + lastDirectory + "</font>";
        directoryParts[directoryParts.length - 1] = formattedLastDirectory;
        final String formattedDirectory = String.join("/", directoryParts);

        sb.append(
                """
                        <table id="overview_%s" style="display: %s" class="overview">
                            <tr>
                                <td class="important">Directory</td>
                                <td>%s</td>
                            </tr>
                            <tr>
                                <td class="important">Date</td>
                                <td>%s</td>
                            </tr>
                            <tr>
                                <td class="important">Time</td>
                                <td>%s</td>
                            </tr>
                            <tr>
                                <td class="important">Span</td>
                                <td>%s days</td>
                            </tr>
                            <tr>
                                <td class="important">Pass Rate</td>
                                <td>%s%%</td>
                            </tr>
                            <tr>
                                <td class="important">Total</td>
                                <td>%s</td>
                            </tr>
                            <tr>
                                <td class="important">Passing</td>
                                <td>%s</td>
                            </tr>
                            <tr>
                                <td class="important">Failing</td>
                                <td>%s</td>
                            </tr>
                            <tr>
                                <td class="important">Unknown</td>
                                <td>%s</td>
                            </tr>
                        </table>
                        """
                        .formatted(
                                owner,
                                hidden ? "none" : "block",
                                formattedDirectory,
                                date,
                                time,
                                data.reportSpan(),
                                percentPassing == -1 ? "--" : percentPassing,
                                testCount.uniqueTests(),
                                testCount.passingTests(),
                                testCount.failingTests(),
                                testCount.unknownTests()));
    }

    private static void generateOverviews(
            @NonNull final StringBuilder sb,
            @NonNull final JrsReportData data,
            @NonNull final List<String> owners,
            @NonNull final Map<String, TestCount> testCountMap) {

        generateOverview(sb, data, "all", testCountMap.get("all"), false);
        generateOverview(sb, data, "unassigned", testCountMap.get(""), true);
        for (final String owner : owners) {
            generateOverview(sb, data, owner, testCountMap.get(owner), true);
        }
    }

    private static void generateOrderControls(@NonNull final StringBuilder sb) {
        sb.append(
                """
                        <center><h1><b>Ordering</b></h1></center>
                        <form autocomplete="off">
                            <input type="radio" name="order" onclick="sortByName()" checked> <span class="important">name</span><br>
                            <input type="radio" name="order" onclick="sortByAge()"> <span class="important">age</span><br>
                            <input type="radio" name="order" onclick="sortByStatus()"> <span class="important">status</span><br>
                        </form>
                        """);
    }

    private static void generateOwnerControls(
            @NonNull final StringBuilder sb,
            @NonNull final List<String> owners,
            @NonNull final Map<String, TestCount> testCountMap) {

        sb.append(
                """
                        <center><h1><b>Owner</b></h1></center>
                        <form autocomplete="off">
                        <input type="radio" name="order" value="name" onclick="switchToOwnerView('all')" checked> <span class="important">all</span> (%s)<br>
                        <input type="radio" name="order" value="age" onclick="switchToOwnerView('')"> <span class="important">unassigned</span> (%s)<br>
                        """
                        .formatted(
                                testCountMap.get("all").uniqueTests(),
                                testCountMap.get("").uniqueTests()));

        for (final String owner : owners) {
            sb.append(
                    """
                            <input type="radio" name="order" value="status" onclick="switchToOwnerView('%s')"> <span class="important">%s</span> (%s)<br>
                            """
                            .formatted(owner, owner, testCountMap.get(owner).uniqueTests()));
        }
        sb.append("</form>\n");
    }

    @NonNull
    private static List<String> findOwners(
            @NonNull final JrsReportData data, @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata) {

        final Set<String> owners = new HashSet<>();

        for (final JrsTestResult result : data.testResults()) {
            final JrsTestMetadata testMetadata = metadata.get(result.id());
            if (testMetadata != null && !testMetadata.owner().isBlank()) {
                owners.add(testMetadata.owner());
            }
        }

        final List<String> ownerList = new ArrayList<>(owners);
        ownerList.sort(String::compareTo);
        return ownerList;
    }

    private static void generateSidePanel(
            @NonNull final StringBuilder sb,
            @NonNull final JrsReportData data,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata) {

        final List<String> owners = findOwners(data, metadata);
        final Map<String, TestCount> testCountMap = countTests(data.testResults(), metadata, owners);

        sb.append("<div class=\"sidePanel\">\n");
        generateOverviews(sb, data, owners, testCountMap);
        sb.append("<br><hr>");
        generateOrderControls(sb);
        sb.append("<br><hr>");
        generateOwnerControls(sb, owners, testCountMap);
        sb.append("</div>\n");
    }

    private static void generateBody(
            @NonNull final StringBuilder sb,
            @NonNull final JrsReportData data,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata,
            @NonNull final Instant now,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement) {

        sb.append(
                """
                        <center>
                        <table class="topLevelTable">
                        <tr>
                        <td class="leftColumn">
                        """);
        generateDataTable(sb, data, metadata, now, bucketPrefix, bucketPrefixReplacement);
        sb.append("""
                </td>
                <td class="rightColumn">
                """);
        generateSidePanel(sb, data, metadata);
        sb.append(
                """
                        </td>
                        </tr>
                        </table>
                        </center>
                        """);
    }

    private static void generatePage(
            @NonNull final StringBuilder sb,
            @NonNull final JrsReportData data,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata,
            @NonNull final Instant now,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement) {

        sb.append("<!DOCTYPE html>\n");
        sb.append("<html>\n");
        generateHeader(sb, now);
        sb.append("""
                <body onload="onLoad()">
                """);
        generateBody(sb, data, metadata, now, bucketPrefix, bucketPrefixReplacement);
        sb.append("</body>\n");
        sb.append("</html>\n");
    }

    /**
     * Print some warnings if we are missing metadata or if we have metadata for tests that were not discovered.
     *
     * @param tests    all tests discovered by this utility
     * @param metadata test metadata, by test
     */
    public static void validateMetadata(
            @NonNull final Set<JrsTestIdentifier> tests,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata) {

        final Set<JrsTestIdentifier> unassignedMetadata = new HashSet<>(metadata.keySet());
        final List<JrsTestIdentifier> testsWithoutMetadata = new ArrayList<>();

        for (final JrsTestIdentifier test : tests) {
            final boolean noteFound = unassignedMetadata.remove(test);

            if (!noteFound) {
                testsWithoutMetadata.add(test);
            }
        }

        if (!testsWithoutMetadata.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            sb.append("The following test(s) do not have metadata:\n");
            for (final JrsTestIdentifier test : testsWithoutMetadata) {
                sb.append("  - ")
                        .append(test.panel())
                        .append(": ")
                        .append(test.name())
                        .append("\n");
            }
            System.out.println(sb);
        }
        if (!unassignedMetadata.isEmpty()) {
            final StringBuilder sb = new StringBuilder();
            sb.append("There is metadata defined for the following test(s), "
                    + "but these test(s) were not discovered during scan:\n");
            for (final JrsTestIdentifier test : unassignedMetadata) {
                sb.append("  - ")
                        .append(test.panel())
                        .append(": ")
                        .append(test.name())
                        .append("\n");
            }
            System.out.println(sb);
        }
    }

    @NonNull
    private static List<JrsTestReportRow> buildTableRows(
            @NonNull final List<JrsTestResult> results,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata) {

        // Sort tests by unique type.
        final Map<JrsTestIdentifier, List<JrsTestResult>> resultsByTestType = new HashMap<>();
        for (final JrsTestResult result : results) {
            final JrsTestIdentifier id = result.id();
            final List<JrsTestResult> resultsForType = resultsByTestType.computeIfAbsent(id, k -> new ArrayList<>());
            resultsForType.add(result);
        }

        // Sort each test of the same type by timestamp.
        for (final List<JrsTestResult> tests : resultsByTestType.values()) {
            Collections.sort(tests);
        }

        final List<JrsTestReportRow> rows = new ArrayList<>();
        for (final JrsTestIdentifier testType : resultsByTestType.keySet()) {
            rows.add(new JrsTestReportRow(resultsByTestType.get(testType), metadata.getOrDefault(testType, null)));
        }

        validateMetadata(resultsByTestType.keySet(), metadata);

        return rows;
    }

    public static void generateReport(
            @NonNull final JrsReportData data,
            @NonNull final Map<JrsTestIdentifier, JrsTestMetadata> metadata,
            @NonNull final Instant now,
            @NonNull final String bucketPrefix,
            @NonNull final String bucketPrefixReplacement,
            @NonNull final Path outputFile) {

        final StringBuilder sb = new StringBuilder();
        generatePage(sb, data, metadata, now, bucketPrefix, bucketPrefixReplacement);

        final String reportString = sb.toString();
        try {
            Files.write(outputFile, reportString.getBytes());
        } catch (final IOException e) {
            throw new UncheckedIOException("unable to generate test report", e);
        }
    }
}
