// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The ConfigProvider interface is used to provide the configuration. This interface can be seen as the "config
 * facility". Whenever you want to access a configuration property that can change at runtime you should not store the
 * {@link Configuration} instance.
 */
public interface ConfigProvider {

    /**
     * Returns the configuration.
     *
     * @return the configuration
     */
    @NonNull
    VersionedConfiguration getConfiguration();
}
