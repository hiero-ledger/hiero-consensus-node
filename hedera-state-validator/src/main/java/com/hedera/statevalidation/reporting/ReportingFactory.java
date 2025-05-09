/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.statevalidation.reporting;

import static com.hedera.statevalidation.Constants.REPORT_FILE;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;

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
