// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import com.hedera.services.bdd.spec.props.JutilPropertySource;

/**
 * Initialize static properties for the HapiPropertySource.
 */
public class HapiPropertySourceStaticInitializer {
    public static final int SHARD;
    public static final long REALM;
    public static final String SHARD_AND_REALM;

    static {
        HapiPropertySource defaultSource = new JutilPropertySource("spec-default.properties");
        SHARD = Integer.parseInt(defaultSource.get("default.shard"));
        REALM = Long.parseLong(defaultSource.get("default.realm"));
        SHARD_AND_REALM = SHARD + "." + REALM + ".";
    }
}
