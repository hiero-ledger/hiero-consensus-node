// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit;

/**
 * A network configuration override.
 */
public @interface ConfigOverride {
    String key();

    String value();
}
