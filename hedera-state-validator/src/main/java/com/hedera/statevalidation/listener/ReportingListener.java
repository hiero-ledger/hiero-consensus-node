// SPDX-License-Identifier: Apache-2.0
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
        log.info(
                "Writing JSON report to [{}]",
                Constants.REPORT_FILE.toAbsolutePath().toString());

        JsonHelper.writeReport(ReportingFactory.getInstance().report(), Constants.REPORT_FILE);
    }
}
