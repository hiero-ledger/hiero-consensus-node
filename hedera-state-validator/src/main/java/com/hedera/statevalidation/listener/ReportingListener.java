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

package com.hedera.statevalidation.listener;

import com.hedera.statevalidation.Constants;
import com.hedera.statevalidation.reporting.JsonHelper;
import com.hedera.statevalidation.reporting.ReportingFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.launcher.TestPlan;

/**
 * This class is used to generate the JSON report after the test plan execution is finished.
 */
public class ReportingListener implements org.junit.platform.launcher.TestExecutionListener {

    private static final Logger log = LogManager.getLogger(ReportingListener.class);

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        log.info("Writing JSON report to [{}]", Constants.REPORT_FILE.toAbsolutePath().toString());

        JsonHelper.writeReport(ReportingFactory.getInstance().report(), Constants.REPORT_FILE);
    }
}
