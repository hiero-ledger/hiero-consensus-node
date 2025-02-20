// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Config for address books and utilities that deal with address books.
 *
 * @param updateAddressBookOnlyAtUpgrade
 * 		If true, then don't change the working address book unless the network restarts with a higher software version.
 * 		Intermediate workaround until the platform is capable of handling an address book that changes at runtime. This
 * 		feature will be removed in a future version. Check if the address book override is enabled. If enabled, then
 * 		the address book will only change when a node starts up. This feature will be removed in a future version.
 * @param forceUseOfConfigAddressBook
 *      If true, then the address book from the config file will be used instead of the address book from the
 *      signed state and the swirld state will not be queried for any address book updates.
 * @param addressBookDirectory
 *      The directory where address book files are saved.
 * @param maxRecordedAddressBookFiles
 *      The maximum number of address book files to keep in the address book directory. On startup the
 *      AddressBookInitializer will create two new files in the `/data/saved/address_book` directory. The
 *      AddressBookInitializer will delete files by age, oldest first, if the number of files in the directory
 *      exceeds the maximum indicated by this setting.
 */
@ConfigData("addressBook")
public record AddressBookConfig(
        @ConfigProperty(defaultValue = "true") boolean updateAddressBookOnlyAtUpgrade,
        @ConfigProperty(defaultValue = "false") boolean forceUseOfConfigAddressBook,
        @ConfigProperty(defaultValue = "data/saved/address_book") String addressBookDirectory,
        @ConfigProperty(defaultValue = "50") int maxRecordedAddressBookFiles,
        @ConfigProperty(defaultValue = "true") boolean createCandidateRosterOnPrepareUpgrade) {}
