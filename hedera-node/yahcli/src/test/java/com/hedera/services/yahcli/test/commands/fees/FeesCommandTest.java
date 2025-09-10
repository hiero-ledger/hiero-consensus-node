// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.ExceptionMsgUtils;
import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

class FeesCommandTest extends YahcliTestBase {

    private static final String[] VALID_SERVICES = new String[] {
        "crypto",
        "consensus", 
        "token",
        "file",
        "contract",
        "scheduled",
        "all"
    };

    private static final String[] INVALID_SERVICES = {
        "CRYPTO", // wrong case (should be lowercase)
        "Consensus", // mixed case
        "TOKEN", // wrong case (should be lowercase)
        "File", // mixed case  
        "CONTRACT", // wrong case (should be lowercase)
        "Scheduled", // mixed case
        "ALL", // wrong case (should be lowercase)
        "crypto-service", // hyphenated
        "crypto_service", // underscored
        "cryptoservice", // concatenated
        "cryptos", // plural
        "consensus-service", // hyphenated
        "consensus_service", // underscored
        "consensusservice", // concatenated
        "consensuses", // plural
        "tokens", // plural
        "files", // plural
        "contracts", // plural
        "scheduleds", // plural
        "alls", // plural
        "unknown", // unknown service
        "invalid", // invalid service
        "service", // generic term
        "network", // different domain
        "balance", // different operation
        "account", // different domain
        "key", // different domain
        "🔥", // emoji
        "123", // numbers
        "", // empty string
        " ", // blank string
        "\t", // tab character
        "\n", // newline character
        " crypto ", // with spaces
        "crypto,consensus", // comma-separated
        "crypto;consensus", // semicolon-separated
        "crypto|consensus" // pipe-separated
    };

    @SuppressWarnings("unused")
    static Stream<Arguments> validServices() {
        return Stream.of(VALID_SERVICES).map(Arguments::of);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> invalidServices() {
        return Stream.of(INVALID_SERVICES).map(Arguments::of);
    }

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " fees help");
            assertCommandHierarchyOf(result, "yahcli", "fees", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " fees help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli fees [COMMAND]", "Commands:");
        }
    }

    @Nested
    class ListBasePricesCommandParams {
        @Test
        void servicesIsRequiredParam() {
            final var exception = assertThrows(
                    CommandLine.MissingParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " fees list-base-prices"));
            ExceptionMsgUtils.assertMissingRequiredParamMsg(exception, "services");
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.fees.FeesCommandTest#validServices")
        void parsesValidSingleService(final String service) {
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices " + service);
            final var cmdSpec = findSubcommand(result, "list-base-prices");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("fees");
        }

        @Test  
        void parsesMultipleValidServices() {
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices crypto consensus token");
            final var cmdSpec = findSubcommand(result, "list-base-prices");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("fees");
        }

        @Test
        void parsesAllValidServicesTogether() {
            final String allServices = String.join(" ", VALID_SERVICES);
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices " + allServices);
            final var cmdSpec = findSubcommand(result, "list-base-prices");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("fees");
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.fees.FeesCommandTest#invalidServices")
        @Disabled("(FUTURE) Add validation for service names - currently accepts any string")
        void recognizesInvalidService(final String testValue) {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " fees list-base-prices " + testValue));
            ExceptionMsgUtils.assertInvalidOptionMsg(exception, testValue);
            assertThat(exception.getMessage()).contains("Invalid value for param 'services': " + testValue);
        }

        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices crypto");
            assertCommandHierarchyOf(result, "yahcli", "fees", "list-base-prices");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices crypto");
            final var subCmd = findSubcommand(result, "list-base-prices").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices help");
            assertCommandHierarchyOf(result, "yahcli", "fees", "list-base-prices", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " fees list-base-prices help");
            assertThat(result).isEqualTo(0);
            assertHasContent("List base prices for all operations", "<services>", "or 'all' to get fees");
        }

        @Test
        void multipleServicesWithInvalidServiceMixed() {
            // This test documents current behavior - invalid services are accepted
            // (FUTURE) When validation is added, this should be updated to expect an exception
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices crypto INVALID consensus");
            final var cmdSpec = findSubcommand(result, "list-base-prices");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("fees");
        }

        @Test
        void emptyStringAsService() {
            // This test documents current behavior - empty strings are accepted
            // (FUTURE) When validation is added, this should be updated to expect an exception
            final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices \"\"");
            final var cmdSpec = findSubcommand(result, "list-base-prices");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("fees");
        }
    }

    @Test
    void registersAllSubcommands() {
        final var EXPECTED_SUBCOMMANDS = List.of("help", "list-base-prices");
        final var feesCommands = testSubjectCL().getSubcommands().get("fees").getSubcommands();
        assertThat(feesCommands).hasSize(EXPECTED_SUBCOMMANDS.size());
        assertThat(feesCommands.keySet()).containsAll(EXPECTED_SUBCOMMANDS);
    }

    @Test
    void callInvocationWithoutSubcommandThrowsException() {
        // Test that calling "fees" directly without a subcommand throws the expected exception
        // Parse to get to the fees subcommand
        final var result = parseArgs(typicalGlobalOptions() + " fees");
        final var feesSubcommand = result.subcommand();
        
        final var exception = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> feesSubcommand.commandSpec().userObject().getClass().getMethod("call").invoke(feesSubcommand.commandSpec().userObject()));
        assertThat(exception.getCause()).isInstanceOf(CommandLine.ParameterException.class);
        assertThat(exception.getCause().getMessage()).contains("Please specify a fee subcommand!");
    }

    @Test
    void parsesMainFeesCommandHierarchy() {
        final var result = parseArgs(typicalGlobalOptions() + " fees list-base-prices crypto");
        assertCommandHierarchyOf(result, "yahcli", "fees", "list-base-prices");
    }
}
