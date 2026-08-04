// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.framework.config;

import java.util.function.Supplier;

/**
 * Default naming configuration for thread configuration, if somebody forgets to set explicit one
 * Every thread name is generated as "unnamed", even if multiple threads with counter are requested
 */
public class UndefinedThreadNameProvider implements Supplier<String> {

    public static final UndefinedThreadNameProvider INSTANCE = new UndefinedThreadNameProvider();

    public static UndefinedThreadNameProvider instance() {
        return INSTANCE;
    }

    @Override
    public String get() {
        return "<unnamed>";
    }
}
