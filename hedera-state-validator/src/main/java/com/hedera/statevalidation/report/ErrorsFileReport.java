// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.report;

import com.hedera.statevalidation.validator.util.ValidationException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Helper class to write validation errors into text file.
 */
public final class ErrorsFileReport {

    private static final Logger log = LogManager.getLogger(ErrorsFileReport.class);

    private static final String REPORT_FILE = "validation_errors.txt";

    private ErrorsFileReport() {}

    /**
     * Writes validation exceptions into file, if passed exceptions list is not empty.
     * Each exception is written into file in a format {validatorName}:{errorMessage}.
     *
     * @param exceptions list of validation exception to be written, could be empty but not {@code null}
     */
    public static void writeErrorsToFile(final Collection<ValidationException> exceptions) {
        if (exceptions.isEmpty()) {
            return;
        }

        try (final var writer = new FileWriter(REPORT_FILE)) {
            for (final var exception : exceptions) {
                writer.write(exception.getValidatorName() + ':' + exception.getMessage());
            }
        } catch (final IOException e) {
            // do not propagate the exception, just log it since the main validation process is already completed.
            log.error("Failed to write validation errors into file {}", REPORT_FILE, e);
        }
    }
}
