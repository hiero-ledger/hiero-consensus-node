// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.listener;

import static com.hedera.statevalidation.listener.ListenerUtils.extractTestFullName;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/**
 * Prints a summary of the test execution to the console.
 */
public class SummaryGeneratingListener implements TestExecutionListener {

    private static final Logger log = LogManager.getLogger(SummaryGeneratingListener.class);

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_YELLOW = "\u001B[33m";
    public static final String ANSI_GREEN = "\u001B[32m";

    private volatile boolean failed = false;
    private final ThreadLocal<Long> startTimestamp = new ThreadLocal<>();

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        startTimestamp.set(System.currentTimeMillis());
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (!testIdentifier.isTest()) {
            if (testExecutionResult.getStatus() == TestExecutionResult.Status.FAILED) {
                testExecutionResult.getThrowable().ifPresent(Throwable::printStackTrace);
                failed = true;
            }
            return;
        }
        long timeTakenSec = (System.currentTimeMillis() - startTimestamp.get()) / 1000;

        switch (testExecutionResult.getStatus()) {
            case SUCCESSFUL -> {
                printMessage(testIdentifier, "SUCCEEDED", ANSI_GREEN, timeTakenSec);
            }
            case ABORTED -> {
                printMessage(testIdentifier, "ABORTED", ANSI_YELLOW, timeTakenSec);
            }
            case FAILED -> {
                printMessage(testIdentifier, "FAILED", ANSI_RED, timeTakenSec);
                testExecutionResult.getThrowable().ifPresent(Throwable::printStackTrace);
                failed = true;
            }
            default ->
                throw new PreconditionViolationException(
                        "Unsupported execution status:" + testExecutionResult.getStatus());
        }
    }

    private static void printMessage(TestIdentifier testIdentifier, String message, String color, long timeTaken) {
        log.info(String.format(
                "%s - %s%s%s, time taken - %s sec",
                extractTestFullName(testIdentifier), color, message, ANSI_RESET, timeTaken));
    }

    public boolean isFailed() {
        return failed;
    }
}
