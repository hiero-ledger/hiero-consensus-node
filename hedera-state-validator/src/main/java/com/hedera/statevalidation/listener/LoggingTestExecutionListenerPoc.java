// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.listener;

import com.hedera.statevalidation.validators.ValidationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logs the start and end of each test. Helps to see clearly the log boundaries of each test.
 */
public class LoggingTestExecutionListenerPoc implements ValidationListener {

    private static final Logger log = LogManager.getLogger(LoggingTestExecutionListenerPoc.class);

    @Override
    public void onValidationStarted(String tag) {
        log.debug(framedString(tag + " started"));
    }

    @Override
    public void onValidationCompleted(String tag) {
        log.debug(framedString(tag + " finished"));
    }

    @Override
    public void onValidationFailed(ValidationException error) {
        log.debug(framedString(error.getValidatorTag() + " finished"));
    }

    private String framedString(String stringToFrame) {
        String frame = " ".repeat(stringToFrame.length() + 6);
        return String.format("\n%s\n   %s   \n%s", frame, stringToFrame, frame);
    }
}
