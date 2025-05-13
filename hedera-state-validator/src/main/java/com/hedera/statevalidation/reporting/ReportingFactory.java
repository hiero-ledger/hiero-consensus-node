// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.reporting;

import static com.hedera.statevalidation.Constants.REPORT_FILE;

import java.io.IOException;
import java.nio.file.Files;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReportingFactory {

    private static final Logger log = LogManager.getLogger(ReportingFactory.class);

    // implement a singleton pattern for this class
    private static ReportingFactory instance;

    private Report report;

    ReportingFactory() {
        if (REPORT_FILE.toFile().exists()) {
            log.info("Found previous report file: {}", REPORT_FILE.toFile().getAbsolutePath());
            String fileContents;
            try {
                fileContents = Files.readString(REPORT_FILE);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            report = JsonHelper.readJSON(fileContents, Report.class);
        } else {
            report = new Report();
        }
    }

    public static ReportingFactory getInstance() {
        if (instance == null) instance = new ReportingFactory();
        return instance;
    }

    public Report report() {
        return report;
    }
}
