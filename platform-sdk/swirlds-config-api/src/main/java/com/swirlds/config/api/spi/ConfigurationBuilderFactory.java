// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.api.spi;

import com.swirlds.config.api.ConfigurationBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This is the SPI (see {@link java.util.ServiceLoader}) interface that an implementation of the config API needs to
 * provide.
 */
public interface ConfigurationBuilderFactory {

    /**
     * By calling this method a new {@link ConfigurationBuilder} instance is created and returned.
     *
     * @return a new {@link ConfigurationBuilder} instance
     */
    @NonNull
    ConfigurationBuilder create();
}
