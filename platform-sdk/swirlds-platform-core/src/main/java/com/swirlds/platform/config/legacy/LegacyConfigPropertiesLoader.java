// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.config.legacy;

import static com.swirlds.base.utility.FileSystemUtils.waitForPathPresence;
import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.utility.CommonUtils;
import com.swirlds.platform.system.address.AddressBookUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Loader that load all properties form the config.txt file
 *
 * @deprecated will be replaced by the {@link com.swirlds.config.api.Configuration} API in near future once the
 * config.txt has been migrated to the regular config API. If you need to use this class please try to do as less static
 * access as possible.
 */
@Deprecated(forRemoval = true)
public final class LegacyConfigPropertiesLoader {

    private static final String ERROR_CONFIG_TXT_NOT_FOUND_BUT_EXISTS =
            "Config.txt file was not found but File#exists() claimed the file does exist";
    private static final String ERROR_ADDRESS_COULD_NOT_BE_PARSED = "'address' could not be parsed";
    private static final Logger logger = LogManager.getLogger(LegacyConfigPropertiesLoader.class);

    private LegacyConfigPropertiesLoader() {}

    /**
     * @throws NullPointerException   in case {@code configPath} parameter is {@code null}
     * @throws ConfigurationException in case {@code configPath} cannot be found in the system
     */
    public static LegacyConfigProperties loadConfigFile(@NonNull final Path configPath) throws ConfigurationException {
        Objects.requireNonNull(configPath, "configPath must not be null");

        // Load config.txt file, parse application jar file name, main class name, address book, and parameters
        if (!waitForPathPresence(configPath)) {
            throw new ConfigurationException(
                    "ERROR: Configuration file not found: %s".formatted(configPath.toString()));
        }
        try {
            final LegacyConfigProperties legacyConfigProperties = new LegacyConfigProperties();
            final String content = Files.readString(configPath);
            final AddressBook addressBook = AddressBookUtils.parseAddressBookText(content);

            if (addressBook.getSize() > 0) {
                legacyConfigProperties.setAddressBook(addressBook);
            }
            return legacyConfigProperties;
        } catch (final FileNotFoundException ex) {
            // this should never happen
            logger.error(EXCEPTION.getMarker(), ERROR_CONFIG_TXT_NOT_FOUND_BUT_EXISTS, ex);
            throw new IllegalStateException(ERROR_CONFIG_TXT_NOT_FOUND_BUT_EXISTS, ex);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (ParseException e) {
            logger.error(EXCEPTION.getMarker(), ERROR_ADDRESS_COULD_NOT_BE_PARSED, e);
            throw new ConfigurationException(ERROR_ADDRESS_COULD_NOT_BE_PARSED, e);
        }
    }

    private static void onError(String message) {
        CommonUtils.tellUserConsolePopup("Error", message);
    }
}
