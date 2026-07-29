// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

/**
 * Utility class for naming threads managed by thread configuration
 *
 */
public interface ThreadNamingConfiguration {

    /**
     * Generate name for a new thread
     * @return new thread name
     */
    String generateNextThreadName();

    /**
     * Enabling numbering threads, in case more than one should be created
     */
    void enableThreadNumbering();
}
