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
     * Loads the private key for the given contract number, if one is available.
     * @param number the contract number
     * @param type the expected type of the private key
     * @return the private key
     * @param <T> the type of the private key
     * @throws IllegalArgumentException if no key of the expected type is found
     */
    <T extends PrivateKey> T loadContractKey(long number, Class<T> type);

    /**
     * Loads the admin private key for the given topic number, if one is available.
     * @param number the topic number
     * @param type the expected type of the admin private key
     * @return the private key
     * @param <T> the type of the private key
     * @throws IllegalArgumentException if no key of the expected type is found
     */
    <T extends PrivateKey> T loadTopicAdminKey(long number, Class<T> type);

    /**
     * Loads the private key for the given file number, if one is available.
     * @param number the file number
     * @param type the expected type of the private key
     * @return the private key
     * @param <T> the type of the private key
     * @throws IllegalArgumentException if no key of the expected type is found
     */
    <T extends PrivateKey> T loadFileKey(long number, Class<T> type);

    /**
     * Exports the private key for the given account name to the appropriate yahcli location.
     * @param spec the spec with the registered key
     * @param name the name of the account whose key to export
     */
    void exportAccountKey(HapiSpec spec, String name);

    /**
     * Exports the private key for the given contract name to the appropriate yahcli location.
     * @param spec the spec with the registered key
     * @param name the name of the contract whose key to export
     */
    void exportContractKey(HapiSpec spec, String name);

    /**
     * Exports the private key for the given topic admin name to the appropriate yahcli location.
     * @param spec the spec with the registered key
     * @param name the name of the topic whose admin key should be exported
     */
    void exportTopicAdminKey(HapiSpec spec, String name);

    /**
     * Exports the first private key in the key list for the given file name to the appropriate yahcli location.
     * @param spec the spec with the registered key
     * @param name the name of the file whose first WACL key should be exported
     */
    void exportFirstFileWaclKey(HapiSpec spec, String name);
}
