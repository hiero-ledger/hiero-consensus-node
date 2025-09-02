// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.bdd;

import static java.util.Objects.requireNonNull;

import com.hedera.services.yahcli.commands.ivy.scenarios.Scenarios;
import com.hedera.services.yahcli.commands.ivy.scenarios.ScenariosConfig;
import com.hedera.services.yahcli.config.domain.GlobalConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;

public class YahcliVerbs {
    private static final Pattern NEW_ACCOUNT_PATTERN = Pattern.compile("account num=(\\d+)");
    private static final Pattern ACCOUNT_BALANCE_PATTERN = Pattern.compile("\\d+\\.\\d+\\.(\\d+)\\s*\\|\\s*(\\d+)");
    private static final Pattern CURRENCY_TRANSFER_PATTERN =
            Pattern.compile("SUCCESS - sent (\\d+) ([a-z]{1,4}bar) to account \\d+\\.\\d+\\.(\\d+)");
    private static final Pattern TOKEN_TRANSFER_PATTERN =
            Pattern.compile("SUCCESS - sent (\\d+) (\\d+) to account \\d+\\.\\d+\\.(\\d+)");

    public static final AtomicReference<String> DEFAULT_CONFIG_LOC = new AtomicReference<>();
    public static final AtomicReference<String> DEFAULT_WORKING_DIR = new AtomicReference<>();
    public static final String TEST_NETWORK = "hapi";

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
     * Returns an operation that invokes a yahcli {@code ivy} subcommand with the given args,
     * taking the config location and working directory from defaults if not overridden.
     * @return the operation
     */
    public static YahcliCallOperation yahcliIvy(@NonNull final String... args) {
        requireNonNull(args);
        return new YahcliCallOperation(prepend(args, "ivy"));
    }

    /**
     * Returns an operation that will load the yahcli global config and pass it to the given callback.
     * @param cb the callback to accept the config
     * @return the operation
     */
    public static YahcliConfigOperation withYahcliConfig(@NonNull final Consumer<GlobalConfig> cb) {
        requireNonNull(cb);
        return new YahcliConfigOperation(cb);
    }

    /**
     * Returns an operation that will load the yahcli scenarios config and pass it to the given
     * callback.
     * @param cb the callback to accept the config
     * @return the operation
     */
    public static YahcliScenariosConfigOperation withYahcliScenariosConfig(
            @NonNull final Consumer<ScenariosConfig> cb) {
        requireNonNull(cb);
        return new YahcliScenariosConfigOperation(false, null, cb);
    }

    /**
     * Returns an operation that will load the yahcli scenarios config and pass it to the given
     * callback.
     * @param cb the callback to accept the config
     * @return the operation
     */
    public static YahcliScenariosConfigOperation assertYahcliScenarios(@NonNull final Consumer<Scenarios> cb) {
        requireNonNull(cb);
        return new YahcliScenariosConfigOperation(false, cb, null);
    }

    /**
     * Returns an operation that will delete any existing yahcli scenarios {@code config.yml}.
     * @return the operation
     */
    public static YahcliScenariosConfigOperation deleteYahcliScenariosConfig() {
        return new YahcliScenariosConfigOperation(true, null, null);
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
     * Returns a callback that will parse multiple account balances in the output, and pass the
     * balances—keyed by account number—to a callback
     * @param cb the callback to capture the account balances
     * @return the output consumer
     */
    public static Consumer<String> newBalanceCapturer(@NonNull final Consumer<Map<Long, Long>> cb) {
        return output -> {
            final Map<Long, Long> balancesByAcctNum = new HashMap<>();
            final var m = ACCOUNT_BALANCE_PATTERN.matcher(output);
            while (m.find()) {
                balancesByAcctNum.put(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)));
            }
            if (!balancesByAcctNum.isEmpty()) {
                cb.accept(balancesByAcctNum);
            } else {
                Assertions.fail("Expected '" + output + "' to contain '" + ACCOUNT_BALANCE_PATTERN.pattern() + "'");
            }
        };
    }

    /**
     * Returns a callback that parses values from a currency (i.e. hbar, tinybar, or kilobar) crypto transfer.
     * For the token equivalent, see {@link #newTokenTransferCapturer(Consumer)}.
     * @param cb the callback to capture the transfer outputs
     * @return the output consumer
     */
    public static Consumer<String> newCurrencyTransferCapturer(@NonNull final Consumer<CryptoTransferOutput> cb) {
        return output -> {
            final var m = CURRENCY_TRANSFER_PATTERN.matcher(output);
            if (m.find()) {
                cb.accept(new CryptoTransferOutput(Long.parseLong(m.group(1)), m.group(2), Long.parseLong(m.group(3))));
            } else {
                Assertions.fail("Expected '" + output + "' to contain '" + CURRENCY_TRANSFER_PATTERN.pattern() + "'");
            }
        };
    }

    /**
     * Like {@link #newCurrencyTransferCapturer(Consumer)}, but for token transfers.
     * @param cb the callback to capture the transfer outputs
     * @return the output consumer
     */
    public static Consumer<String> newTokenTransferCapturer(@NonNull final Consumer<CryptoTransferOutput> cb) {
        return output -> {
            final var m = TOKEN_TRANSFER_PATTERN.matcher(output);
            if (m.find()) {
                cb.accept(new CryptoTransferOutput(Long.parseLong(m.group(1)), m.group(2), Long.parseLong(m.group(3))));
            } else {
                Assertions.fail("Expected '" + output + "' to contain '" + TOKEN_TRANSFER_PATTERN.pattern() + "'");
            }
        };
    }

    // Note: denom can be hbar|kilobar|tinybar or a token number
    public record CryptoTransferOutput(long amount, String denom, long toAcctNum) {}

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

    /**
     * Sets the default config location to use for yahcli operations.
     * @param configLoc the config location
     */
    public static void setDefaultConfigLoc(@NonNull final String configLoc) {
        requireNonNull(configLoc);
        DEFAULT_CONFIG_LOC.set(requireNonNull(configLoc));
    }

    /**
     * Sets the default working directory to use for yahcli operations.
     * @param workingDir the working directory
     */
    public static void setDefaultWorkingDir(@NonNull final String workingDir) {
        requireNonNull(workingDir);
        DEFAULT_WORKING_DIR.set(requireNonNull(workingDir));
    }

    /**
     * Builds the absolute <b>filename</b> of a given key relative to Yahcli's default network. The
     * resulting format should be an absolute path ending with {@code <default network name>/keys},
     * e.g. {@code localhost/keys}.
     * @param keyFile the file name of the key
     * @return the path of the key file
     */
    public static String asYcDefaultNetworkKey(@NonNull final String keyFile) {
        requireNonNull(keyFile);
        return Path.of(absDefaultNetworkKeysDir(), keyFile).toString();
    }

    private static String absDefaultNetworkKeysDir() {
        return Path.of(DEFAULT_WORKING_DIR.get(), TEST_NETWORK, "keys")
                .toAbsolutePath()
                .toString();
    }
}
