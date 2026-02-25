// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.listener;

import static com.hedera.statevalidation.util.junit.JUnitUtils.extractTestFullName;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Logs the start and end of each test. Helps to see clearly the log boundaries of each test.
 */
public class LoggingTestExecutionListener implements TestExecutionListener {

    private static final Logger log = LogManager.getLogger(LoggingTestExecutionListener.class);

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (testIdentifier.isTest()) {
            log.debug(framedString(extractTestFullName(testIdentifier) + " started"));
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (testIdentifier.isTest()) {
            log.debug(framedString(extractTestFullName(testIdentifier) + " finished"));
        }
    }

    private String framedString(String stringToFrame) {
        String frame = " ".repeat(stringToFrame.length() + 6);
        return String.format("\n%s\n   %s   \n%s", frame, stringToFrame, frame);
    }
}
