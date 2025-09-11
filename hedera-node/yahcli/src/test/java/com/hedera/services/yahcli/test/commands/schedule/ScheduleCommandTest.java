// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

public class ScheduleCommandTest extends YahcliTestBase {

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule help");
            assertCommandHierarchyOf(result, "yahcli", "schedule", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " schedule help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli schedule [COMMAND]", "Commands:");
        }
    }

    @Nested
    class SignCommandParams {
        private static final String[] VALID_SCHEDULE_IDS =
                new String[] {"0.0.123", "1.2.345", "123", "0.0.9999", "999.888.777"};

        @SuppressWarnings("unused")
        static Stream<Arguments> validScheduleIds() {
            return Stream.of(VALID_SCHEDULE_IDS).map(Arguments::of);
        }

        @ParameterizedTest
        @MethodSource("validScheduleIds")
        void parsesScheduleIds(String scheduleId) {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign --scheduleId " + scheduleId);
            final var optValue = findOption(result, "sign", "--scheduleId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo(scheduleId);
        }

        @Test
        void parsesCommandHierarchy() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign");
            assertCommandHierarchyOf(result, "yahcli", "schedule", "sign");
        }

        @Test
        void registersAllSubcommands() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign");
            final var subCmd = findSubcommand(result, "sign").orElseThrow();
            assertThat(subCmd.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign help");
            assertCommandHierarchyOf(result, "yahcli", "schedule", "sign", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " schedule sign help");
            assertThat(result).isEqualTo(0);
            assertHasContent("Usage: yahcli schedule sign", "Sign a transaction with schedule id", "Commands:");
        }

        @Test
        void scheduleIdOptionParsesCorrectly() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign --scheduleId 0.0.123");
            final var optValue = findOption(result, "sign", "--scheduleId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("0.0.123");
        }

        @Test
        void scheduleIdOptionIsOptional() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign");
            final var optValue = findOption(result, "sign", "--scheduleId");
            assertThat(optValue).isPresent();
            assertThat((String) optValue.get().getValue()).isNull();
        }

        @Test
        void parsesNumericScheduleId() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign --scheduleId 123");
            final var optValue = findOption(result, "sign", "--scheduleId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("123");
        }

        @Test
        void parsesFqScheduleId() {
            final var result = parseArgs(typicalGlobalOptions() + " schedule sign --scheduleId 1.2.345");
            final var optValue = findOption(result, "sign", "--scheduleId").orElseThrow();
            assertThat((String) optValue.getValue()).isEqualTo("1.2.345");
        }
    }

    @Nested
    class ScheduleCommandNegativeTests {

        @Test
        void scheduleIdIsRequiredWhenSpecified() {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " schedule sign --scheduleId"));
            assertThat(exception.getMessage()).contains("Missing required parameter for option '--scheduleId'");
        }
    }
}
