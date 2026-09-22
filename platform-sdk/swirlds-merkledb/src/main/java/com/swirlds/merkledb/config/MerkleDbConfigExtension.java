// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * {@link ConfigurationExtension} for the MerkleDB, allowing to fetch {@link MerkleDbConfig}.
 */
public class MerkleDbConfigExtension implements ConfigurationExtension {

    @NonNull
    @Override
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(MerkleDbConfig.class);
    }
}
