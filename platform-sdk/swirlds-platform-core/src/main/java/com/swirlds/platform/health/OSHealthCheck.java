// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

/**
 * Performs an OS health check and reports the results
 */
@FunctionalInterface
public interface OSHealthCheck {

    /**
     * Performs a single OS health check and reports the result to a {@link StringBuilder}.
     *
     * @param sb
     * 		the string builder to append the report to
     * @param config
     * 		config values for determining if a check passes or fails
     * @return {@code true} if the check passes, {@code false} otherwise
     */
    boolean performCheckAndReport(StringBuilder sb, OSHealthCheckConfig config);
}
