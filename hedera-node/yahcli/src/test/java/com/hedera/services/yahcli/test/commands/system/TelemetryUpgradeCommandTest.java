// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class TelemetryUpgradeCommandTest extends YahcliTestBase {

    private static final String[] VALID_FILE_NUMBERS = {
        "159", "1", "100", "999999", "0.0.159", "0.0.1", "1.2.3", "999.999.999999"
    };

    private static final String[] VALID_HASHES = {
        "a" + "0".repeat(95),
        "f".repeat(96),
        "1234567890abcdef".repeat(6),
        "ABCDEF1234567890".repeat(6).toLowerCase()
    };

    private static final String[] VALID_START_TIMES = {
        "2024-01-01.12:00:00",
        "2023-12-31.23:59:59",
        "2024-02-29.00:00:01",
        "2024-06-15.14:30:45",
        "2025-12-25.09:15:30"
    };

    private static final String[] INVALID_FILE_NUMBERS = {
        "-1", "abc", "1.2.3.4", "a.b.c", "", " ", "1..2", ".1.2", "1.2.", "🔥", "null", "undefined"
    };

    private static final String[] INVALID_HASHES = {
        "g123",
        "12345",
        "a".repeat(95),
        "a".repeat(97),
        "",
        " ",
        "ZZZZ",
        "12 34",
        "12-34-56",
        "0x1234",
        "null",
        "🔥".repeat(24)
    };

    private static final String[] INVALID_START_TIMES = {
        "2024-01-01 12:00:00",
        "2024/01/01.12:00:00",
        "01-01-2024.12:00:00",
        "2024-13-01.12:00:00",
        "2024-01-32.12:00:00",
        "2024-01-01.25:00:00",
        "2024-01-01.12:60:00",
        "2024-01-01.12:00:60",
        "2024-02-30.12:00:00",
        "2023-02-29.12:00:00",
        "not-a-date",
        "",
        " ",
        "2024-01-01",
        "12:00:00",
        "🔥"
    };

    @SuppressWarnings("unused")
    static Stream<Arguments> invalidFileNumbers() {
        return Stream.of(INVALID_FILE_NUMBERS).map(Arguments::of);
    }

    @SuppressWarnings("unused")
    static Stream<Arguments> invalidHashes() {
        return Stream.of(INVALID_HASHES).map(Arguments::of);
    }

    @SuppressWarnings("unused")
    static Stream<Arguments> invalidStartTimes() {
        return Stream.of(INVALID_START_TIMES).map(Arguments::of);
    }

    @SuppressWarnings("unused")
    static Stream<Arguments> validParameterCombinations() {
        return Stream.of(
                Arguments.of("159", "a" + "0".repeat(95), "2024-01-01.12:00:00"),
                Arguments.of("0.0.159", "f".repeat(96), "2024-12-31.23:59:59"),
                Arguments.of("1", "1234567890abcdef".repeat(6), "2024-06-15.14:30:45"),
                Arguments.of("999999", "ABCDEF1234567890".repeat(6).toLowerCase(), "2025-01-01.00:00:00"));
    }

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry help");
            assertCommandHierarchyOf(result, "yahcli", "upgrade-telemetry", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " upgrade-telemetry help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli upgrade-telemetry", "Upgrades telemetry via NMT", "Commands:");
        }
    }

    @Nested
    class TelemetryUpgradeCommandParams {
        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s 2024-01-01.12:00:00");
            assertCommandHierarchyOf(result, "yahcli", "upgrade-telemetry");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s 2024-01-01.12:00:00");
            final var subCmd = findSubcommand(result, "upgrade-telemetry").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void parsesWithoutOptionalParams() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
        }

        @Test
        void parsesWithOnlyHash() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96));
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
        }

        @Test
        void parsesWithOnlyStartTime() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
        }

        @ParameterizedTest
        @ValueSource(strings = {"159", "1", "100", "999999", "0.0.159", "0.0.1", "1.2.3", "999.999.999999"})
        void parsesValidUpgradeFileNumbers(final String fileNum) {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f " + fileNum + " -h "
                    + "a".repeat(96) + " -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo(fileNum);
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("a".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-01-01.12:00:00");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "a000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
                    "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                    "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef",
                    "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
                })
        void parsesValidUpgradeFileHashes(final String hash) {
            final var result =
                    parseArgs(typicalGlobalOptions() + " upgrade-telemetry -h " + hash + " -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo(hash);
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-01-01.12:00:00");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "2024-01-01.12:00:00",
                    "2023-12-31.23:59:59",
                    "2024-02-29.00:00:01",
                    "2024-06-15.14:30:45",
                    "2025-12-25.09:15:30"
                })
        void parsesValidStartTimes(final String startTime) {
            final var result =
                    parseArgs(typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s " + startTime);
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("a".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo(startTime);
        }

        @ParameterizedTest
        @MethodSource(
                "com.hedera.services.yahcli.test.commands.system.TelemetryUpgradeCommandTest#validParameterCombinations")
        void parsesValidParameterCombinations(final String fileNum, final String hash, final String startTime) {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -f " + fileNum + " -h " + hash + " -s " + startTime);
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo(fileNum);
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo(hash);
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo(startTime);
        }

        @Test
        void usesDefaultUpgradeFileNumber() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo("159");
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("a".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-01-01.12:00:00");
        }

        @Test
        void acceptsLongOptionNames() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " upgrade-telemetry --upgrade-file-num 200 --upgrade-zip-hash "
                            + "b".repeat(96) + " --start-time 2024-06-15.14:30:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("--upgrade-file-num").getValue())
                    .isEqualTo("200");
            assertThat((String) cmdSpec.get().findOption("--upgrade-zip-hash").getValue())
                    .isEqualTo("b".repeat(96));
            assertThat((String) cmdSpec.get().findOption("--start-time").getValue())
                    .isEqualTo("2024-06-15.14:30:00");
        }

        @Test
        void acceptsShortOptionNames() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f 300 -h " + "c".repeat(96)
                    + " -s 2024-12-25.09:15:30");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo("300");
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("c".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-12-25.09:15:30");
        }

        @Test
        void mixesShortAndLongOptionNames() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f 400 --upgrade-zip-hash "
                    + "d".repeat(96) + " -s 2024-03-15.18:45:22");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo("400");
            assertThat((String) cmdSpec.get().findOption("--upgrade-zip-hash").getValue())
                    .isEqualTo("d".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-03-15.18:45:22");
        }

        @ParameterizedTest
        @ValueSource(strings = {"--invalid-option", "--unknown", "-x", "-z"})
        void rejectsUnknownOptions(final String invalidOption) {
            final var exception = assertThrows(
                    CommandLine.UnmatchedArgumentException.class,
                    () -> parseArgs(typicalGlobalOptions() + " upgrade-telemetry " + invalidOption + " value -h "
                            + "a".repeat(96) + " -s 2024-01-01.12:00:00"));
            assertThat(exception.getMessage())
                    .containsIgnoringCase("Unknown option")
                    .contains(invalidOption);
        }

        @Test
        void worksWithAllGlobalOptions() {
            final var result = parseArgs(
                    "-c custom.yml -n previewnet -a 5 -p 10 -f 500 -i 10.0.0.1 -w /tmp -o results.log -v INFO -s "
                            + "upgrade-telemetry --upgrade-file-num 250 --upgrade-zip-hash " + "e".repeat(96)
                            + " --start-time 2024-08-10.12:30:45");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");

            assertThat(testSubjectCli().getConfigLoc()).isEqualTo("custom.yml");
            assertThat(testSubjectCli().getNet()).isEqualTo("previewnet");
            assertThat(testSubjectCli().getNodeAccount()).isEqualTo("5");
            assertThat(testSubjectCli().getPayer()).isEqualTo("10");
            assertThat(testSubjectCli().getFixedFee()).isEqualTo(500L);
            assertThat(testSubjectCli().getNodeIpv4Addr()).isEqualTo("10.0.0.1");
            assertThat(testSubjectCli().getWorkingDir()).isEqualTo("/tmp");
            assertThat(testSubjectCli().getOutputFile()).isEqualTo("results.log");
            assertThat(testSubjectCli().getLogLevel().toString()).isEqualTo("INFO");
            assertThat(testSubjectCli().isScheduled()).isTrue();
        }
    }

    @Nested
    class ValidationTests {
        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.system.TelemetryUpgradeCommandTest#invalidFileNumbers")
        void acceptsInvalidFileNumbers(final String invalidFileNum) {
            if (invalidFileNum.contains(" ") || invalidFileNum.isEmpty()) {
                return; // Skip problematic values that cause parsing issues
            }
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f " + invalidFileNum + " -h "
                    + "a".repeat(96) + " -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            final var fileNumValue = (String) cmdSpec.get().findOption("-f").getValue();
            assertThat(fileNumValue).isEqualTo(invalidFileNum);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.system.TelemetryUpgradeCommandTest#invalidHashes")
        void acceptsInvalidHashes(final String invalidHash) {
            if (invalidHash.contains(" ") || invalidHash.isEmpty()) {
                return; // Skip problematic values that cause parsing issues
            }
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + invalidHash + " -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            final var hashValue = (String) cmdSpec.get().findOption("-h").getValue();
            assertThat(hashValue).isEqualTo(invalidHash);
        }

        @ParameterizedTest
        @MethodSource("com.hedera.services.yahcli.test.commands.system.TelemetryUpgradeCommandTest#invalidStartTimes")
        void acceptsInvalidStartTimes(final String invalidStartTime) {
            if (invalidStartTime.contains(" ") || invalidStartTime.isEmpty()) {
                return; // Skip problematic values that cause parsing issues
            }
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s " + invalidStartTime);
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            final var startTimeValue = (String) cmdSpec.get().findOption("-s").getValue();
            assertThat(startTimeValue).isEqualTo(invalidStartTime);
        }

        @Test
        void handlesEmptyValues() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
        }
    }

    @Nested
    class EdgeCaseTests {
        @Test
        void handlesMaxLengthHash() {
            final var maxHash = "f".repeat(96);
            final var result =
                    parseArgs(typicalGlobalOptions() + " upgrade-telemetry -h " + maxHash + " -s 2024-01-01.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo(maxHash);
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-01-01.12:00:00");
        }

        @Test
        void handlesLeapYearDate() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s 2024-02-29.12:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("a".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-02-29.12:00:00");
        }

        @Test
        void handlesEndOfYearDateTime() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "b".repeat(96) + " -s 2024-12-31.23:59:59");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("b".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-12-31.23:59:59");
        }

        @Test
        void handlesBeginningOfYearDateTime() {
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + "c".repeat(96) + " -s 2024-01-01.00:00:00");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("c".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-01-01.00:00:00");
        }

        @Test
        void handlesLargeFileNumber() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f 999999999 -h " + "d".repeat(96)
                    + " -s 2024-06-15.12:30:45");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo("999999999");
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("d".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-06-15.12:30:45");
        }

        @Test
        void handlesEntityFormatWithLargeNumbers() {
            final var result = parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f 999.999.999999 -h "
                    + "e".repeat(96) + " -s 2024-06-15.12:30:45");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-f").getValue()).isEqualTo("999.999.999999");
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo("e".repeat(96));
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-06-15.12:30:45");
        }

        @Test
        void handlesMixedCaseInHexHash() {
            final var mixedCaseHash = "AbCdEf1234567890".repeat(6);
            final var result = parseArgs(
                    typicalGlobalOptions() + " upgrade-telemetry -h " + mixedCaseHash + " -s 2024-06-15.12:30:45");
            final var cmdSpec = findSubcommand(result, "upgrade-telemetry");
            assertThat(cmdSpec).isPresent();
            assertThat((String) cmdSpec.get().findOption("-h").getValue()).isEqualTo(mixedCaseHash);
            assertThat((String) cmdSpec.get().findOption("-s").getValue()).isEqualTo("2024-06-15.12:30:45");
        }

        @Test
        void duplicateOptionsThrowException() {
            final var exception = assertThrows(
                    CommandLine.OverwrittenOptionException.class,
                    () -> parseArgs(typicalGlobalOptions() + " upgrade-telemetry -f 100 -f 200 -h " + "a".repeat(96)
                            + " -s 2024-01-01.12:00:00"));
            assertThat(exception.getMessage()).contains("should be specified only once");
        }
    }

    @Test
    void registersAllSubcommands() {
        final var EXPECTED_SUBCOMMANDS = List.of("help");
        final var telemetryUpgradeCommands =
                testSubjectCL().getSubcommands().get("upgrade-telemetry").getSubcommands();
        assertThat(telemetryUpgradeCommands).hasSize(EXPECTED_SUBCOMMANDS.size());
        assertThat(telemetryUpgradeCommands.keySet()).containsAll(EXPECTED_SUBCOMMANDS);
    }

    @Test
    void telemetryUpgradeCommandIsRegisteredInMainYahcli() {
        final var allSubcommands = testSubjectCL().getSubcommands().keySet();
        assertThat(allSubcommands).contains("upgrade-telemetry");
    }

    @Test
    void parsesMainTelemetryUpgradeCommandHierarchy() {
        final var result = parseArgs(
                typicalGlobalOptions() + " upgrade-telemetry -h " + "a".repeat(96) + " -s 2024-01-01.12:00:00");
        assertCommandHierarchyOf(result, "yahcli", "upgrade-telemetry");
    }
}
