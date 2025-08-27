// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import java.nio.file.Path;

public class Constants {
    public static final Path REPORT_FILE = Path.of("report.json");
    public static final Path COMBINED_REPORT_FILE = Path.of("combined_report.json");
    public static final String COMBINED_HTML_REPORT_TEMPLATE = "combined_report.html.template";
    public static final Path COMBINED_HTML_REPORT = Path.of("combined_report.html");
    public static final String VM_LABEL = "state";
}
