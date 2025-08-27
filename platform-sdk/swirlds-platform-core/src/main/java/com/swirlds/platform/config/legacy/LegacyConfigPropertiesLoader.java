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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Scanner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.roster.Address;
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

    private static final String APP_PROPERTY_NAME = "app";
    private static final String ADDRESS_PROPERTY_NAME = "address";
    private static final String SWIRLD_PROPERTY_NAME = "swirld";

    public static final String ERROR_CONFIG_TXT_NOT_FOUND_BUT_EXISTS =
            "Config.txt file was not found but File#exists() claimed the file does exist";
    public static final String ERROR_MORE_THAN_ONE_APP =
            "config.txt had more than one line starting with 'app'. All but the last will be ignored.";
    public static final String ERROR_NO_PARAMETER = "%s needs a parameter";
    public static final String ERROR_ADDRESS_COULD_NOT_BE_PARSED = "'address' could not be parsed";
    public static final String ERROR_PROPERTY_NOT_KNOWN =
            "'%s' in config.txt isn't a recognized first parameter for a line";
    public static final String ERROR_NEXT_NODE_NOT_GREATER_THAN_HIGHEST_ADDRESS =
            "The next node ID must be greater than the highest node ID used for addresses";
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

        final LegacyConfigProperties configurationProperties = new LegacyConfigProperties();

        try (final Scanner scanner = new Scanner(configPath, StandardCharsets.UTF_8)) {
            final AddressBook addressBook = new AddressBook();
            long lineNumber = 0;
            while (scanner.hasNextLine()) {
                final String line = readNextLine(scanner);
                lineNumber++;
                if (!line.isEmpty()) {
                    final String[] lineParameters = splitLine(line);
                    final int len = Math.max(10, lineParameters.length);
                    // pars is the comma-separated parameters, trimmed, lower-cased, then padded with "" to have
                    // at least 10 parameters
                    final String[] pars = new String[len];
                    final String[] parsOriginalCase = new String[len];
                    for (int i = 0; i < len; i++) {
                        parsOriginalCase[i] = i >= lineParameters.length ? "" : lineParameters[i].trim();
                        pars[i] = parsOriginalCase[i].toLowerCase(Locale.ENGLISH);
                    }
                    switch (pars[0]) {
                        case SWIRLD_PROPERTY_NAME ->
                            setSwirldName(configurationProperties, lineParameters.length, parsOriginalCase[1]);
                        case APP_PROPERTY_NAME -> {
                            if (configurationProperties.appConfig().isPresent()) {
                                onError(ERROR_MORE_THAN_ONE_APP);
                            }
                            final String[] appParams = Arrays.copyOfRange(lineParameters, 2, lineParameters.length);
                            final JarAppConfig appConfig = new JarAppConfig(lineParameters[1], appParams);
                            configurationProperties.setAppConfig(appConfig);
                        }
                        case ADDRESS_PROPERTY_NAME -> {
                            try {
                                final Address address = AddressBookUtils.parseAddressText(line);
                                if (address != null) {
                                    addressBook.add(address);
                                }
                            } catch (final ParseException ex) {
                                // if we fail to parse address, we must abort since otherwise node starts with subset
                                // of node keys and fails to join the network eventually.
                                throw new ConfigurationException(
                                        String.format(
                                                "%s [line: %d]: %s",
                                                ERROR_ADDRESS_COULD_NOT_BE_PARSED, lineNumber, line),
                                        ex);
                            }
                        }
                        case "nextnodeid" -> {
                            // As of release 0.56, nextNodeId is not used and ignored.
                            // CI/CD pipelines need to be updated to remove this field from files.
                            // Future Work: remove this case when nextNodeId is no longer present in CI/CD pipelines.
                        }
                        default -> onError(ERROR_PROPERTY_NOT_KNOWN.formatted(pars[0]));
                    }
                }
            }
            if (addressBook.getSize() > 0) {
                configurationProperties.setAddressBook(addressBook);
            }
            return configurationProperties;
        } catch (final FileNotFoundException ex) {
            // this should never happen
            logger.error(EXCEPTION.getMarker(), ERROR_CONFIG_TXT_NOT_FOUND_BUT_EXISTS, ex);
            throw new IllegalStateException(ERROR_CONFIG_TXT_NOT_FOUND_BUT_EXISTS, ex);
        } catch (final IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String readNextLine(final Scanner scanner) {
        final String line = scanner.nextLine();
        final int pos = line.indexOf("#");
        if (pos > -1) {
            return line.substring(0, pos).trim();
        }
        return line.trim();
    }

    private static void handleParam(final String propertyName, final int paramLength, final Runnable action) {
        if (paramLength >= 2) {
            action.run();
        } else {
            onError(ERROR_NO_PARAMETER.formatted(propertyName));
        }
    }

    private static void setSwirldName(
            final LegacyConfigProperties configurationProperties, final int paramLength, final String value) {
        handleParam(SWIRLD_PROPERTY_NAME, paramLength, () -> configurationProperties.setSwirldName(value));
    }

    private static void onError(String message) {
        CommonUtils.tellUserConsolePopup("Error", message);
    }

    /**
     * Split the given string on its commas, and trim each result
     *
     * @param line the string of comma-separated values to split
     * @return the array of trimmed elements.
     */
    @NonNull
    private static String[] splitLine(@NonNull final String line) {
        Objects.requireNonNull(line);

        final String[] elms = line.split(",");
        for (int i = 0; i < elms.length; i++) {
            elms[i] = elms[i].trim();
        }

        return elms;
    }
}
