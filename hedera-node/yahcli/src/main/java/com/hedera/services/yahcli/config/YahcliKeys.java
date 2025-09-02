// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.config;

import com.hedera.services.bdd.spec.HapiSpec;
import java.security.PrivateKey;

/**
 * Abstraction for loading and exporting keys for yahcli.
 */
public interface YahcliKeys {
    /**
     * Loads the private key for the given account number, if one is available.
     * @param number the account number
     * @param type the expected type of the private key
     * @return the private key
     * @param <T> the type of the private key
     * @throws IllegalArgumentException if no key of the expected type is found
     */
    <T extends PrivateKey> T loadAccountKey(long number, Class<T> type);

    /**
     * Exports the private key for the given account name to the appropriate yahcli location.
     * @param spec the spec with the registered key
     * @param name the name of the account whose key to export
     */
    void exportAccountKey(HapiSpec spec, String name);
}
