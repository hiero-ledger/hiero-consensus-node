// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.Configuration;

/**
 * An interface that extends the {@link Configuration} interface with a version.
 */
public interface VersionedConfiguration extends Configuration {

    /**
     * Returns the version of the configuration.
     *
     * @return the version of the configuration
     */
    long getVersion();
}
