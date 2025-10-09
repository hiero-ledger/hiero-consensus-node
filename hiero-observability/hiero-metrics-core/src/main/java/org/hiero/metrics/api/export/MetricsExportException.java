// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export;

/**
 * Exception to indicate problems during metrics export.
 */
public class MetricsExportException extends Exception {

    public MetricsExportException(String message) {
        super(message);
    }

    public MetricsExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
