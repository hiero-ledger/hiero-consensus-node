// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.legacy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.roster.SimpleAddresses;

/**
 * Bean for all parameters that can be part of the config.txt file
 *
 * @deprecated will be replaced by the {@link com.swirlds.config.api.Configuration} API in near future once the
 * 		config.txt has been migrated to the regular config API. If you need to use this class please try to do as less
 * 		static access as possible.
 */
@Deprecated(forRemoval = true)
public record LegacyConfigProperties(SimpleAddresses simpleAddresses) {

    @NonNull
    public SimpleAddresses getSimpleAddresses() {
        if (simpleAddresses == null) {
            return new SimpleAddresses(List.of());
        }
        return simpleAddresses;
    }
}
