// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.listener;

import com.hedera.statevalidation.poc.util.ValidationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// update logging format
public class ValidationExecutionListener implements ValidationListener {

    private static final Logger log = LogManager.getLogger(ValidationExecutionListener.class);

    private volatile boolean failed = false;

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
        this.failed = true;
        log.debug(framedString(error.getValidatorTag() + " failed"));
    }

    private String framedString(String stringToFrame) {
        String frame = " ".repeat(stringToFrame.length() + 6);
        return String.format("\n%s\n   %s   \n%s", frame, stringToFrame, frame);
    }

    public boolean isFailed() {
        return failed;
    }
}
