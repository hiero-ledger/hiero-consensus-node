// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.listener;

import com.hedera.statevalidation.validators.ValidationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Prints a summary of the test execution to the console.
 */
public class SummaryGeneratingListenerPoc implements ValidationListener {

    private static final Logger log = LogManager.getLogger(SummaryGeneratingListenerPoc.class);

    public static final String ANSI_RESET = "\u001B[0m";
    public static final String ANSI_RED = "\u001B[31m";
    public static final String ANSI_GREEN = "\u001B[32m";

    private volatile boolean failed = false;
    private final ThreadLocal<Long> startTimestamp = new ThreadLocal<>();

    @Override
    public void onValidationStarted(String tag) {
        startTimestamp.set(System.currentTimeMillis());
    }

    @Override
    public void onValidationCompleted(String tag) {
        long timeTakenSec = (System.currentTimeMillis() - startTimestamp.get()) / 1000;
        printMessage(tag, "SUCCEEDED", ANSI_GREEN, timeTakenSec);
    }

    @Override
    public void onValidationFailed(ValidationException error) {
        failed = true;
        long timeTakenSec = (System.currentTimeMillis() - startTimestamp.get()) / 1000;
        printMessage(error.getValidatorTag(), "FAILED", ANSI_RED, timeTakenSec);
        error.printStackTrace();
    }

    private static void printMessage(String tag, String message, String color, long timeTaken) {
        log.info(String.format("%s - %s%s%s, time taken - %s sec", tag, color, message, ANSI_RESET, timeTaken));
    }

    public boolean isFailed() {
        return failed;
    }
}
