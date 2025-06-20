// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.provider;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A log provider factory that can be used to create log providers. The factory uses SPI.
 */
public interface LogProviderFactory {

    /**
     * Creates a new log provider.
     *
     * @param configuration the configuration
     * @return the log provider
     */
    @NonNull
    LogProvider create(@NonNull Configuration configuration);
}
