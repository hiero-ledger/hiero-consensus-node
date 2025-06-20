// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.legacy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.Optional;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Bean for all parameters that can be part of the config.txt file
 *
 * @deprecated will be replaced by the {@link com.swirlds.config.api.Configuration} API in near future once the
 * 		config.txt has been migrated to the regular config API. If you need to use this class please try to do as less
 * 		static access as possible.
 */
@Deprecated(forRemoval = true)
public class LegacyConfigProperties {

    private String swirldName = null;

    private JarAppConfig appConfig = null;

    private AddressBook addressBook = null;

    /**
     * Set the address book.
     *
     * @param addressBook the address book
     */
    public void setAddressBook(@NonNull final AddressBook addressBook) {
        Objects.requireNonNull(addressBook, "addressBook");
        this.addressBook = addressBook.copy();
    }

    /**
     * Get the address book. If no address book is set, an empty address book is returned.
     *
     * @return the address book
     */
    @NonNull
    public AddressBook getAddressBook() {
        if (addressBook == null) {
            return new AddressBook();
        }
        return addressBook.copy();
    }

    /**
     * @throws NullPointerException in case {@code appConfig} parameter is {@code null}
     */
    public void setAppConfig(final JarAppConfig appConfig) {
        this.appConfig = Objects.requireNonNull(appConfig, "appConfig must not be null");
    }

    /**
     * @throws NullPointerException in case {@code swirldName} parameter is {@code null}
     */
    public void setSwirldName(final String swirldName) {
        this.swirldName = Objects.requireNonNull(swirldName, "swirldName must not be null");
    }

    public Optional<String> swirldName() {
        return Optional.ofNullable(swirldName);
    }

    public Optional<JarAppConfig> appConfig() {
        return Optional.ofNullable(appConfig);
    }
}
