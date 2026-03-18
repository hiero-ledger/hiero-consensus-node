// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Warning-only password policy checks for keystore passphrases.
 *
 * <p>This class intentionally does not enforce or reject weak passwords. It only logs warnings to help operators
 * detect insecure configuration.
 */
public final class KeystorePasswordPolicy {
    private static final Logger logger = LogManager.getLogger(KeystorePasswordPolicy.class);

    private KeystorePasswordPolicy() {}

    /** Recommended minimum passphrase length for keystore passwords. */
    private static final int MIN_LENGTH = 12;

    /**
     * Logs a warning if the provided keystore password does not meet the recommended policy.
     *
     * <p>This check is advisory only and does not reject non-compliant passwords.
     *
     * @param configKey the configuration key associated with the password value
     * @param password the password to evaluate
     */
    public static void warnIfNonCompliant(@NonNull final String configKey, @NonNull final String password) {
        Objects.requireNonNull(configKey, "configKey must not be null");
        Objects.requireNonNull(password, "password must not be null");

        final List<String> issues = issues(password);
        if (issues.isEmpty()) {
            return;
        }

        logger.warn(
                "Configured {} does not meet recommended password policy ({}). This is not enforced, but weak "
                        + "keystore passwords increase risk of offline brute-force attacks.",
                configKey,
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
