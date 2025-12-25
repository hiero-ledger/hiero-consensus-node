// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto.config;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the crypto module.
 */
@AutoService(ConfigurationExtension.class)
public class CryptoConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(CryptoConfig.class);
    }
}
