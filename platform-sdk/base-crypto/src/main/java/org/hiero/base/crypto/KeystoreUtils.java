// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.crypto.config.CryptoConfig;
import org.hiero.base.crypto.config.CryptoConfig_;

/**
 * Warning-only password policy checks for keystore passphrases.
 *
 */
public final class KeystoreUtils {

    private static final Logger logger = LogManager.getLogger(KeystoreUtils.class);

    private KeystoreUtils() {}

    /** Recommended minimum passphrase length for keystore passwords. */
    private static final int MIN_LENGTH = 12;

    /**
     * Retrieves the keystore password from the configuration and logs a warning if it does not meet the recommended
     * password policy to help operators detect insecure configuration.
     *
     * @param configuration the configuration to retrieve the keystore password from
     * @return the keystore password from the configuration
     * @throws IllegalStateException if the keystore password is {@code null} or blank
     */
    @NonNull
    public static String getConfiguredPassword(@NonNull final Configuration configuration) {
        final CryptoConfig configData = configuration.getConfigData(CryptoConfig.class);
        final String passphrase = configData.keystorePassword();
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException(CryptoConfig_.KEYSTORE_PASSWORD + " must not be null or blank");
        }
        warnIfNonCompliant(passphrase);

        return passphrase;
    }

    static void warnIfNonCompliant(@NonNull final String password) {
        requireNonNull(password, "password must not be null");

        final List<String> issues = issues(password);
        if (issues.isEmpty()) {
            return;
        }

        logger.warn(
                "Configured {} does not meet recommended password policy ({}). This is not enforced, but weak "
                        + "keystore passwords increase risk of offline brute-force attacks.",
                CryptoConfig_.KEYSTORE_PASSWORD,
                String.join(", ", issues));
    }

    @NonNull
    private static List<String> issues(@NonNull final String password) {
        final List<String> issues = new ArrayList<>();

        if (password.length() < MIN_LENGTH) {
            issues.add("minLength>=" + MIN_LENGTH);
        }

        boolean hasUpper = false;
        boolean hasLower = false;
        boolean hasDigit = false;
        boolean hasSpecial = false;

        for (int i = 0; i < password.length(); i++) {
            final char characterAt = password.charAt(i);
            if (Character.isUpperCase(characterAt)) {
                hasUpper = true;
            } else if (Character.isLowerCase(characterAt)) {
                hasLower = true;
            } else if (Character.isDigit(characterAt)) {
                hasDigit = true;
            } else {
                hasSpecial = true;
            }
        }

        if (!hasUpper) {
            issues.add("uppercase");
        }
        if (!hasLower) {
            issues.add("lowercase");
        }
        if (!hasDigit) {
            issues.add("digit");
        }
        if (!hasSpecial) {
            issues.add("special");
        }

        return issues;
    }
}
