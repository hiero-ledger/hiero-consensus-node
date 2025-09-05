// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.ExceptionMsgUtils;
import com.hedera.services.yahcli.test.YahcliTestBase;
import com.hedera.services.yahcli.test.commands.files.dsl.DestDir;
import com.hedera.services.yahcli.test.commands.files.dsl.SourceDir;
import java.io.IOException;
import java.nio.file.Files;
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

class SysFilesCommandTest extends YahcliTestBase {

    private static final String[] VALID_SYSFILE_NAMES = new String[] {
        "address-book",
        "node-details",
        "fees",
        "rates",
        "props",
        "permissions",
        "throttles",
        "software-zip",
        "telemetry-zip",
        "101",
        "102",
        "111",
        "112",
        "121",
        "122",
        "123",
        "150",
        "159"
    };

    private static final String[] INVALID_SYSFILE_NAMES = {
        "-address-book", // correct name, but wrong prefix
        "address-book.json", // correct name, but incorrectly includes extension
        "node_details", // wrong separator char
        "fEeS", // mixed case
        "submission", // random word
        "🔥😅", // special chars
        "THROTTLES", // wrong (UPPER) case
        "zip-software", // words swapped (software-zip)
        "telemetr-zip", // 'telemetry' misspelled
        "-23", // any integer
        "111.0", // decimal number
        "1 1 1", // space-separated number
        "11n2", // not a number
        "1001", // valid number, but not a sys file
        "0.0.121", // incorrectly includes 'shard.realm' (0, 0)
        "1.2.122", // incorrectly includes 'shard.realm' (1, 2)
        "122,123,124", // comma-separated numbers
        "", // empty string
        " \t\n ", // blank string
    };

    private static final String[] CUSTOM_DIRS = new String[] {
        "{network}/custom-system-files",
        "{network}/sysfiles/custom-dir",
        "custom-dir",
        "customized1/{network}/customized2/sysfiles"
    };

    @SuppressWarnings("unused")
    static Stream<Arguments> validSysfiles() {
        return Stream.of(VALID_SYSFILE_NAMES).map(Arguments::of);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> invalidSysfiles() {
        return Stream.of(INVALID_SYSFILE_NAMES).map(Arguments::of);
    }

    @SuppressWarnings("unused")
    private static Stream<Arguments> customDirs() {
        return Stream.of(CUSTOM_DIRS).map(Arguments::of);
    }

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles help");
            assertCommandHierarchyOf(result, "yahcli", "sysfiles", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " sysfiles help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli sysfiles [COMMAND]", "Commands:");
        }
    }

    @Nested
    class SysFileDownloadCommandParams {
        @Test
        void sysfilesIsRequiredParam() {
            final var exception = assertThrows(
                    CommandLine.MissingParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " sysfiles download"));
            ExceptionMsgUtils.assertMissingRequiredParamMsg(exception, "sysfiles");
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.files.SysFilesCommandTest#validSysfiles")
        void parsesValidArgs(final String sysFile) {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles download " + sysFile);
            final var cmdSpec = findSubcommand(result, "download");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("sysfiles");

            // Check default destination directory
            final var destDirOptSpec = cmdSpec.get().findOption(DestDir.LONG_OPT.str());
            assertThat(destDirOptSpec.defaultValue()).isEqualTo("{network}/sysfiles/");
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.files.SysFilesCommandTest#invalidSysfiles")
        @Disabled("(FUTURE) Inform user of invalid sys file args")
        void recognizesInvalidSysFile(final String testValue) {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " sysfiles download " + testValue));
            ExceptionMsgUtils.assertInvalidOptionMsg(exception, testValue);
            assertThat(exception.getMessage()).contains("Invalid value for param 'sysfiles': " + testValue);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.files.SysFilesCommandTest#customDirs")
        void parsesCorrectDestinationDir(String destDir) {
            // `typicalGlobalOptions()` uses `-n testnet`, so any templated value should contain `testnet`
            final var specificDestDir = destDir.replace("{network}", "testnet");
            // The chosen sys file here is arbitrary. We're only focused on parsing the destination directory
            final String cmdPrefix =
                    typicalGlobalOptions() + " sysfiles download address-book " + DestDir.LONG_OPT.str() + " ";

            // Parses valid path
            final CommandLine.ParseResult result = parseArgs(cmdPrefix + specificDestDir);
            final var optValue =
                    findOption(result, "download", DestDir.LONG_OPT.str()).orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(specificDestDir);
        }

        @Test
        void parsesAbsoluteDirAsDestinationDir() throws IOException {
            // Special case for absolute directory
            parsesAbsoluteDir("download", DestDir.LONG_OPT.str());
        }

        @Test
        void verifyDefaultDestDir() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles download all");
            final var optValue =
                    findOption(result, "download", DestDir.LONG_OPT.str()).orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("{network}/sysfiles/");
        }

        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles download node-details");
            assertCommandHierarchyOf(result, "yahcli", "sysfiles", "download");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles download node-details");
            final var subCmd = findSubcommand(result, "download").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles download help");
            assertCommandHierarchyOf(result, "yahcli", "sysfiles", "download", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " sysfiles download help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Downloads system files", "<sysfiles>...   one or more from", "Commands:");
        }
    }

    @Nested
    class SysFileUploadCommandParams {
        @Test
        void sysfilesIsRequiredParam() {
            final var exception = assertThrows(
                    CommandLine.MissingParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " sysfiles upload"));
            ExceptionMsgUtils.assertMissingRequiredParamMsg(exception, "sysfile");
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.files.SysFilesCommandTest#validSysfiles")
        void parsesValidArgs(final String sysFile) {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles upload " + sysFile);
            final var cmdSpec = findSubcommand(result, "upload").orElseThrow();
            assertThat(cmdSpec.parent().name()).isEqualTo("sysfiles");

            // Check default destination directory
            final var destDirOptSpec = cmdSpec.findOption(SourceDir.LONG_OPT.str());
            assertThat(destDirOptSpec.defaultValue()).isEqualTo("{network}/sysfiles/");
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.files.SysFilesCommandTest#invalidSysfiles")
        @Disabled("(FUTURE) Inform user of invalid sys file args")
        void recognizesInvalidSysFile(final String testValue) {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " sysfiles upload " + testValue));
            ExceptionMsgUtils.assertInvalidOptionMsg(exception, testValue);
            assertThat(exception.getMessage()).contains("Invalid value for param 'sysfiles': " + testValue);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.files.SysFilesCommandTest#customDirs")
        void parsesCorrectDestinationDir(String destDir) {
            // `typicalGlobalOptions()` uses `-n testnet`, so any templated value should contain `testnet`
            final var specificDestDir = destDir.replace("{network}", "testnet");
            // The chosen sys file here is arbitrary. We're only focused on parsing the destination directory
            final String cmdPrefix =
                    typicalGlobalOptions() + " sysfiles upload address-book " + SourceDir.LONG_OPT.str() + " ";

            // Parses valid path
            final CommandLine.ParseResult result = parseArgs(cmdPrefix + specificDestDir);
            final var optValue =
                    findOption(result, "upload", SourceDir.LONG_OPT.str()).orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(specificDestDir);
        }

        @Test
        void parsesAbsoluteDirAsDestinationDir() throws IOException {
            // Special case for absolute directory
            parsesAbsoluteDir("upload", SourceDir.LONG_OPT.str());
        }

        @Test
        void verifyDefaultSourceDir() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles upload all");
            final var optValue =
                    findOption(result, "upload", SourceDir.LONG_OPT.str()).orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("{network}/sysfiles/");
        }

        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles upload node-details");
            assertCommandHierarchyOf(result, "yahcli", "sysfiles", "upload");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles upload address-book");
            final var subCmd = findSubcommand(result, "upload").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " sysfiles upload help");
            assertCommandHierarchyOf(result, "yahcli", "sysfiles", "upload", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " sysfiles upload help");
            assertThat(result).isEqualTo(0);
            assertHasContent(
                    "Usage: yahcli sysfiles upload", "Uploads a system file", "<sysfile>   one of", "Commands:");
        }
    }

    @Test
    void registersAllSubcommands() {
        final var EXPECTED_SUBCOMMANDS = List.of("help", "download", "upload", "hash-check");
        final var sysfilesCommands =
                testSubjectCL().getSubcommands().get("sysfiles").getSubcommands();
        assertThat(sysfilesCommands).hasSize(EXPECTED_SUBCOMMANDS.size());
        assertThat(sysfilesCommands.keySet()).containsAll(EXPECTED_SUBCOMMANDS);
    }

    private void parsesAbsoluteDir(final String subCmd, final String option) throws IOException {
        // Special case for absolute directory
        final var tmpDir = Files.createTempDirectory("test-dir");
        final String absoluteDir = tmpDir.toAbsolutePath().toString();

        try {
            final CommandLine.ParseResult result = parseArgs(
                    typicalGlobalOptions() + " sysfiles " + subCmd + " node-details " + option + " " + absoluteDir);
            final var optValue = findOption(result, subCmd, option).orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(absoluteDir);
        } finally {
            // Clean up the temp directory in any case
            Files.deleteIfExists(tmpDir);
        }
    }
}
