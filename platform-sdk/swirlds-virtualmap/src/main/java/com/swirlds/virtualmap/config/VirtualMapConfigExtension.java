// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * {@link ConfigurationExtension} for the MerkleDB, allowing to fetch {@link VirtualMapConfig}.
 */
public class VirtualMapConfigExtension implements ConfigurationExtension {

    @NonNull
    @Override
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(VirtualMapConfig.class, VirtualMapSyncConfig.class);
    }
}
