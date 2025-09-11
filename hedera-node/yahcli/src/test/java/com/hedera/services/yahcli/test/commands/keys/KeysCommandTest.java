// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.keys;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

public class KeysCommandTest extends YahcliTestBase {

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " keys help");
            assertCommandHierarchyOf(result, "yahcli", "keys", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " keys help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli keys [COMMAND]", "Commands:");
        }
    }

    @Nested
    class NewPemCommandParams {

        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new");
            assertCommandHierarchyOf(result, "yahcli", "keys", "gen-new");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new");
            final var subCmd = findSubcommand(result, "gen-new").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new help");
            assertCommandHierarchyOf(result, "yahcli", "keys", "gen-new", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " keys gen-new help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli keys gen-new", "Generates a new ED25519 key", "Commands:");
        }

        @Test
        void pathOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new -p custom/key.pem");
            final var optValue = findOption(result, "gen-new", "--path").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("custom/key.pem");
        }

        @Test
        void passphraseOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new -x mypassword");
            final var optValue = findOption(result, "gen-new", "--passphrase").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("mypassword");
        }

        @Test
        void verifyDefaultPathValue() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new");
            final var optValue = findOption(result, "gen-new", "--path").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/ed25519.pem");
        }

        @Test
        void verifyPassphraseIsOptional() {
            final var result = parseArgs(typicalGlobalOptions() + " keys gen-new");
            final var optValue = findOption(result, "gen-new", "--passphrase");
            // Passphrase option exists but has no value when not specified
            assertThat(optValue).isPresent();
            assertThat(((String) optValue.get().getValue())).isNull();
        }
    }

    @Nested
    class ExtractPublicCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public");
            assertCommandHierarchyOf(result, "yahcli", "keys", "print-public");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public");
            final var subCmd = findSubcommand(result, "print-public").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public help");
            assertCommandHierarchyOf(result, "yahcli", "keys", "print-public", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " keys print-public help");
            assertThat(result).isEqualTo(0);
            assertHasContent(
                    "Usage: yahcli keys print-public",
                    "Prints the public part of a Ed25519 key in PEM or mnemonic form",
                    "Commands:");
        }

        @Test
        void pathOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public -p custom/key.pem");
            final var optValue = findOption(result, "print-public", "--path").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("custom/key.pem");
        }

        @Test
        void passphraseOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public -x mypassword");
            final var optValue =
                    findOption(result, "print-public", "--passphrase").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("mypassword");
        }

        @Test
        void verifyDefaultPathValue() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public");
            final var optValue = findOption(result, "print-public", "--path").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/ed25519.pem");
        }

        @Test
        void verifyPassphraseIsOptional() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-public");
            final var optValue = findOption(result, "print-public", "--passphrase");
            // Passphrase option exists but has no value when not specified
            assertThat(optValue).isPresent();
            assertThat((String) optValue.get().getValue()).isNull();
        }
    }

    @Nested
    class ExtractDetailsCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys");
            assertCommandHierarchyOf(result, "yahcli", "keys", "print-keys");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys");
            final var subCmd = findSubcommand(result, "print-keys").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys help");
            assertCommandHierarchyOf(result, "yahcli", "keys", "print-keys", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " keys print-keys help");
            assertThat(result).isEqualTo(0);
            assertHasContent(
                    "Usage: yahcli keys print-keys",
                    "Prints the public and private keys in a Ed25519 key pair",
                    "Commands:");
        }

        @Test
        void pathOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys -p custom/key.pem");
            final var optValue = findOption(result, "print-keys", "--path").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("custom/key.pem");
        }

        @Test
        void passphraseOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys -x mypassword");
            final var optValue =
                    findOption(result, "print-keys", "--passphrase").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("mypassword");
        }

        @Test
        void verifyDefaultPathValue() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys");
            final var optValue = findOption(result, "print-keys", "--path").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("keys/ed25519.pem");
        }

        @Test
        void verifyPassphraseIsOptional() {
            final var result = parseArgs(typicalGlobalOptions() + " keys print-keys");
            final var optValue = findOption(result, "print-keys", "--passphrase");
            // Passphrase option exists but has no value when not specified
            assertThat(optValue).isPresent();
            assertThat((String) optValue.get().getValue()).isNull();
        }
    }

    @Nested
    class AllSubCommandNegativeTests {

        public static final String[] SUBCOMMANDS = new String[] {"gen-new", "print-public", "print-keys"};

        @SuppressWarnings("unused")
        static Stream<String> subcommandProvider() {
            return Arrays.stream(SUBCOMMANDS);
        }

        @ParameterizedTest
        @MethodSource("subcommandProvider")
        void parsesMissingPathValue(String subcommand) {
            final var command = typicalGlobalOptions() + " keys " + subcommand + " -p";
            final var exception = assertThrows(CommandLine.ParameterException.class, () -> parseArgs(command));
            assertThat(exception.getMessage()).contains("Missing required parameter for option");
        }

        @ParameterizedTest
        @MethodSource("subcommandProvider")
        void parsesMissingPassphraseValue(String subcommand) {
            final var command = typicalGlobalOptions() + " keys " + subcommand + " -p custom/key.pem -x";
            final var exception = assertThrows(CommandLine.ParameterException.class, () -> parseArgs(command));
            assertThat(exception.getMessage()).contains("Missing required parameter for option");
        }

        @ParameterizedTest
        @MethodSource("subcommandProvider")
        void parsesMissingPathPassphraseValue(String subcommand) {
            final var command = typicalGlobalOptions() + " keys " + subcommand + " -p -x";
            final var exception = assertThrows(CommandLine.ParameterException.class, () -> parseArgs(command));
            assertThat(exception.getMessage()).contains("Expected parameter for option '--path' but found '-x'");
        }
    }
}
