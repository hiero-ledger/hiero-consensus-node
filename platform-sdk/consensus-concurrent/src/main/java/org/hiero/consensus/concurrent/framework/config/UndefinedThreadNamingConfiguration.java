// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

/**
 * Default naming configuration for thread configuration, if somebody forgets to set explicit one
 * Every thread name is generated as "unnamed", even if multiple threads with counter are requested
 */
public class UndefinedThreadNamingConfiguration extends AbstractThreadNamingConfiguration {

    public static final UndefinedThreadNamingConfiguration INSTANCE = new UndefinedThreadNamingConfiguration();

    public static UndefinedThreadNamingConfiguration instance() {
        return INSTANCE;
    }

    @Override
    public String generateNextThreadName() {
        return "<unnamed>";
    }
}
