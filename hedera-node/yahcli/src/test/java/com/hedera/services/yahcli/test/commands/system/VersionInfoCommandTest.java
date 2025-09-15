// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class VersionInfoCommandTest extends YahcliTestBase {
    private static final String VERSION_COMMAND = "version";
    private static final String TEST_IP_ADDRESS = "10.0.0.1";

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND + " help");
            assertCommandHierarchyOf(result, "yahcli", VERSION_COMMAND, "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " " + VERSION_COMMAND + " help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli version", "Get the deployed version of a network", "Commands:");
        }
    }

    @Nested
    class VersionCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND);
            assertCommandHierarchyOf(result, "yahcli", VERSION_COMMAND);
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND);
            final var subCmd = findSubcommand(result, VERSION_COMMAND).orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void versionCommandAcceptsNoAdditionalParams() {
            // Version command should work with just global params
            final var result = parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
        }

        @Test
        void versionCommandWithExtraArgsThrowsException() {
            // Version command should not accept extra parameters
            final var exception = assertThrows(
                    CommandLine.UnmatchedArgumentException.class,
                    () -> parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND + " extra-arg"));
            assertThat(exception.getMessage()).contains("Unmatched argument");
            assertThat(exception.getMessage()).contains("extra-arg");
        }

        @Test
        void versionCommandWithMultipleExtraArgsThrowsException() {
            // Version command should not accept multiple extra parameters
            final var exception = assertThrows(
                    CommandLine.UnmatchedArgumentException.class,
                    () -> parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND + " arg1 arg2 arg3"));
            assertThat(exception.getMessage()).contains("Unmatched argument");
            assertThat(exception.getMessage()).containsAnyOf("arg1", "arg2", "arg3");
        }

        @Test
        void versionCommandWithInvalidOptionsThrowsException() {
            // Version command should not accept command-specific options
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " " + VERSION_COMMAND + " --invalid-option"));
            assertThat(exception.getMessage()).containsIgnoringCase("unknown option");
            assertThat(exception.getMessage()).contains("--invalid-option");
        }

        @Test
        void versionCommandInheritsGlobalOptions() {
            // Version command should work with all valid global options
            final var result = parseArgs("-c config.yml -n mainnet -a 3 -p 2 -f 100 " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");

            // Verify global options are parsed correctly
            assertThat(testSubjectCli().getNet()).isEqualTo("mainnet");
            assertThat(testSubjectCli().getConfigLoc()).isEqualTo("config.yml");
            assertThat(testSubjectCli().getNodeAccount()).isEqualTo("3");
            assertThat(testSubjectCli().getPayer()).isEqualTo("2");
            assertThat(testSubjectCli().getFixedFee()).isEqualTo(100L);
        }

        @Test
        void versionCommandWithMinimalGlobalOptions() {
            // Version command should work with minimal required global options
            final var result = parseArgs("-n testnet -p 2 " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
        }

        @Test
        void versionCommandWorksWithScheduleOption() {
            // Version command should work with the schedule option
            final var result = parseArgs(typicalGlobalOptions() + " -s " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(testSubjectCli().isScheduled()).isTrue();
        }

        @Test
        void versionCommandWorksWithWorkingDirOption() {
            // Version command should work with working directory option
            final var result = parseArgs(typicalGlobalOptions() + " -w /custom/path " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(testSubjectCli().getWorkingDir()).isEqualTo("/custom/path");
        }

        @Test
        void versionCommandWorksWithNodeIpOption() {
            // Version command should work with node IP option
            final var result = parseArgs(typicalGlobalOptions() + " -i 127.0.0.1 " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(testSubjectCli().getNodeIpv4Addr()).isEqualTo("127.0.0.1");
        }

        @Test
        void versionCommandWorksWithOutputFileOption() {
            // Version command should work with output file option
            final var result = parseArgs(typicalGlobalOptions() + " -o output.txt " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(testSubjectCli().getOutputFile()).isEqualTo("output.txt");
        }

        @Test
        void versionCommandWorksWithVerboseOption() {
            // Version command should work with verbose/log level option
            final var result = parseArgs(typicalGlobalOptions() + " -v DEBUG " + VERSION_COMMAND);
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(testSubjectCli().getLogLevel().toString()).isEqualTo("DEBUG");
        }

        @Test
        void versionCommandWorksWithAllGlobalOptions() {
            // Version command should work with all global options combined
            final var result = parseArgs(String.format(
                    "-c custom.yml -n previewnet -a 5 -p 10 -f 500 -i %s -w /tmp -o results.log -v INFO -s %s",
                    TEST_IP_ADDRESS, VERSION_COMMAND));
            final var cmdSpec = findSubcommand(result, VERSION_COMMAND);
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");

            // Verify all global options are parsed correctly
            assertThat(testSubjectCli().getConfigLoc()).isEqualTo("custom.yml");
            assertThat(testSubjectCli().getNet()).isEqualTo("previewnet");
            assertThat(testSubjectCli().getNodeAccount()).isEqualTo("5");
            assertThat(testSubjectCli().getPayer()).isEqualTo("10");
            assertThat(testSubjectCli().getFixedFee()).isEqualTo(500L);
            assertThat(testSubjectCli().getNodeIpv4Addr()).isEqualTo(TEST_IP_ADDRESS);
            assertThat(testSubjectCli().getWorkingDir()).isEqualTo("/tmp");
            assertThat(testSubjectCli().getOutputFile()).isEqualTo("results.log");
            assertThat(testSubjectCli().getLogLevel().toString()).isEqualTo("INFO");
            assertThat(testSubjectCli().isScheduled()).isTrue();
        }
    }

    @Test
    void versionCommandIsRegisteredInMainYahcli() {
        // Verify that the version command is properly registered in main yahcli
        final var allSubcommands = testSubjectCL().getSubcommands().keySet();
        assertThat(allSubcommands).contains(VERSION_COMMAND);
    }
}
