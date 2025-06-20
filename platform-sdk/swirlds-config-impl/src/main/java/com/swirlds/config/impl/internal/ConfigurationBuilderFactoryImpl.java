// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.api.spi.ConfigurationBuilderFactory;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementation of the {@link ConfigurationBuilderFactory} interface that will automatically be loaded by Java SPI (see
 * {@link java.util.ServiceLoader}).
 */
@AutoService(ConfigurationBuilderFactory.class)
public final class ConfigurationBuilderFactoryImpl implements ConfigurationBuilderFactory {

    @NonNull
    @Override
    public ConfigurationBuilder create() {
        return new ConfigurationBuilderImpl();
    }
}
