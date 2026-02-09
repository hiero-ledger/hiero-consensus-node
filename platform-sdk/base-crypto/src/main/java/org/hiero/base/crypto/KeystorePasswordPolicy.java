// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.Logger;

/**
 * Warning-only password policy checks for keystore passphrases.
 *
 * <p>This class intentionally does not enforce or reject weak passwords. It only logs warnings to help operators
 * detect insecure configuration.
 */
public final class KeystorePasswordPolicy {
    private KeystorePasswordPolicy() {}

    private static final int MIN_LENGTH = 12;

    public static void warnIfNonCompliant(
            @NonNull final Logger logger, @NonNull final String configKey, @NonNull final String password) {
        Objects.requireNonNull(logger, "logger must not be null");
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
