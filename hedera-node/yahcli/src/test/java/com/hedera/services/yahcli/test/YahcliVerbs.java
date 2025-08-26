// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;

public class YahcliVerbs {
    private static final Pattern NEW_ACCOUNT_PATTERN = Pattern.compile("account num=(\\d+)");

    private YahcliVerbs() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns an operation that invokes a yahcli {@code accounts} subcommand with the given args,
     * taking the config location and working directory from defaults if not overridden.
     * @return the operation
     */
    public static YahcliCallOperation yahcliAccounts(@NonNull final String... args) {
        requireNonNull(args);
        return new YahcliCallOperation(prepend(args, "accounts"));
    }

    /**
     * Returns a callback that will look for a line indicating the creation of a new account,
     * and pass the new account number to the given callback.
     * @param cb the callback to capture the new account number
     * @return the output consumer
     */
    public static Consumer<String> newAccountCapturer(@NonNull final LongConsumer cb) {
        return output -> {
            final var m = NEW_ACCOUNT_PATTERN.matcher(output);
            if (m.find()) {
                cb.accept(Long.parseLong(m.group(1)));
            } else {
                Assertions.fail("Expected '" + output + "' to contain '" + NEW_ACCOUNT_PATTERN.pattern() + "'");
            }
        };
    }

    /**
     * Prepend the given strings to the front of the given array.
     * @param a the array
     * @param ps the strings to prepend
     * @return a new array with the strings prepended
     */
    public static String[] prepend(@NonNull final String[] a, @NonNull final String... ps) {
        requireNonNull(a);
        requireNonNull(ps);
        final var newArgs = new String[a.length + ps.length];
        System.arraycopy(ps, 0, newArgs, 0, ps.length);
        System.arraycopy(a, 0, newArgs, ps.length, a.length);
        return newArgs;
    }
}
