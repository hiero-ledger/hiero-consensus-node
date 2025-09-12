// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class SetupStakeCommandTest extends YahcliTestBase {

    @Nested
    class HelpCommandParams {
        @Test
        void helpCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking help");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking", "help");
        }

        @Test
        void invocationPrintsUsage() {
            final var result = execute(typicalGlobalOptions() + " activate-staking help");
            assertThat(result).isEqualTo(0);
            assertHasContent(
                    "Activates staking on the target network",
                    "per-node-amount",
                    "staking-reward-rate",
                    "reward-account-balance");
        }
    }

    @Nested
    class SetupStakeCommandParams {
        @Test
        void setupStakeCommandParses() {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void subcommandsOfSetupStakeCommand() {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking");
            final var cmdSpec = findSubcommand(result, "activate-staking").orElseThrow();
            assertThat(cmdSpec.subcommands().keySet()).isEqualTo(Set.of("help"));
        }

        @Test
        void setupStakeCommandInheritsGlobalOptions() {
            // SetupStake command should work with all valid global options
            final var result = parseArgs("-c config.yml -n mainnet -a 3 -p 2 -f 100 activate-staking");
            final var cmdSpec = findSubcommand(result, "activate-staking");
            assertThat(cmdSpec).isPresent();
            assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");

            // Verify global options are parsed correctly
            assertThat(testSubjectCli().getNet()).isEqualTo("mainnet");
            assertThat(testSubjectCli().getConfigLoc()).isEqualTo("config.yml");
            assertThat(testSubjectCli().getNodeAccount()).isEqualTo("3");
            assertThat(testSubjectCli().getPayer()).isEqualTo("2");
            assertThat(testSubjectCli().getFixedFee()).isEqualTo(100L);
        }

        @ParameterizedTest
        @ValueSource(strings = {"100", "1000h", "5kh", "10mh", "1bh", "100H", "5KH", "10MH", "1BH", "500", "0"})
        void validPerNodeAmountParses(String amount) {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p " + amount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @ValueSource(strings = {"100", "1000h", "5kh", "10mh", "1bh", "100H", "5KH", "10MH", "1BH", "500", "0"})
        void validStakingRewardRateParses(String amount) {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -r " + amount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @ValueSource(strings = {"100", "1000h", "5kh", "10mh", "1bh", "100H", "5KH", "10MH", "1BH", "500", "0"})
        void validRewardAccountBalanceParses(String amount) {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -b " + amount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void multipleValidOptionsCanBeCombined() {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p 1000h -r 5kh -b 10mh");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void longOptionNamesWork() {
            final var result = parseArgs(
                    typicalGlobalOptions()
                            + " activate-staking --per-node-amount 1000h --staking-reward-rate 5kh --reward-account-balance 10mh");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"invalid", "abc", "100x", "10gh", "5ch", "-100", "100.5", "1e10", "999999999999999999999"})
        void invalidPerNodeAmountParsesButWillFailOnExecution(String invalidAmount) {
            // Invalid values are accepted during parsing - validation happens in call()
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p " + invalidAmount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void emptyPerNodeAmountParsesButWillFailOnExecution() {
            // Empty strings are accepted during parsing - validation happens in call()
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p \"\"");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"invalid", "abc", "100x", "10gh", "5ch", "-100", "100.5", "1e10", "999999999999999999999"})
        void invalidStakingRewardRateParsesButWillFailOnExecution(String invalidAmount) {
            // Invalid values are accepted during parsing - validation happens in call()
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -r " + invalidAmount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void emptyStakingRewardRateParsesButWillFailOnExecution() {
            // Empty strings are accepted during parsing - validation happens in call()
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -r \"\"");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {"invalid", "abc", "100x", "10gh", "5ch", "-100", "100.5", "1e10", "999999999999999999999"})
        void invalidRewardAccountBalanceParsesButWillFailOnExecution(String invalidAmount) {
            // Invalid values are accepted during parsing - validation happens in call()
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -b " + invalidAmount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void emptyRewardAccountBalanceParsesButWillFailOnExecution() {
            // Empty strings are accepted during parsing - validation happens in call()
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -b \"\"");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void duplicatePerNodeAmountOptionsThrowException() {
            final var exception = assertThrows(
                    CommandLine.OverwrittenOptionException.class,
                    () -> parseArgs(typicalGlobalOptions() + " activate-staking -p 100h -p 200h"));
            assertThat(exception.getMessage()).contains("should be specified only once");
        }

        @Test
        void duplicateStakingRewardRateOptionsThrowException() {
            final var exception = assertThrows(
                    CommandLine.OverwrittenOptionException.class,
                    () -> parseArgs(typicalGlobalOptions() + " activate-staking -r 100h -r 200h"));
            assertThat(exception.getMessage()).contains("should be specified only once");
        }

        @Test
        void duplicateRewardAccountBalanceOptionsThrowException() {
            final var exception = assertThrows(
                    CommandLine.OverwrittenOptionException.class,
                    () -> parseArgs(typicalGlobalOptions() + " activate-staking -b 100h -b 200h"));
            assertThat(exception.getMessage()).contains("should be specified only once");
        }

        @Test
        void missingOptionValueThrowsException() {
            final var exception = assertThrows(
                    CommandLine.ParameterException.class,
                    () -> parseArgs(typicalGlobalOptions() + " activate-staking -p"));
            assertThat(exception.getMessage()).contains("Missing required parameter");
        }
    }

    @Nested
    class ScaledAmountValidation {
        @ParameterizedTest
        @MethodSource("validScaledAmounts")
        void validScaledAmountsAccepted(String amount) {
            // Test that valid scaled amounts with proper suffixes are accepted
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p " + amount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @MethodSource("validEdgeCaseAmounts")
        void validEdgeCaseAmountsAccepted(String amount) {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p " + amount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @ParameterizedTest
        @MethodSource("invalidEdgeCaseAmounts")
        void invalidEdgeCaseAmountsParsedButWillFailOnExecution(String amount) {
            // These values are accepted during parsing but would fail during execution
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking -p " + amount);
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void spaceAmountThrowsUnmatchedArgumentException() {
            final var exception = assertThrows(
                    CommandLine.UnmatchedArgumentException.class,
                    () -> parseArgs(typicalGlobalOptions() + " activate-staking -p \" \""));
            assertThat(exception.getMessage()).contains("Unmatched argument");
        }

        static Stream<String> validScaledAmounts() {
            return Stream.of(
                    "1h", "10kh", "100mh", "1000bh", "1H", "10KH", "100MH", "1000BH", "0", "123456789", "999h", "1bh");
        }

        static Stream<String> validEdgeCaseAmounts() {
            return Stream.of("0", "1", "999", "1h", "1000H");
        }

        static Stream<String> invalidEdgeCaseAmounts() {
            return Stream.of("h", "kh", "1x", "1gh", "-1", "1.5", "1e5", "abc", "999999999999999999999");
        }
    }

    @Nested
    class OptionsValidation {
        @Test
        void allOptionsCanBeUsedTogether() {
            final var result = parseArgs(
                    typicalGlobalOptions()
                            + " activate-staking --per-node-amount 1000h --staking-reward-rate 500h --reward-account-balance 250000h");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void optionsWorkWithShortAndLongNames() {
            final var shortResult = parseArgs(typicalGlobalOptions() + " activate-staking -p 100h -r 50h -b 1000h");
            final var longResult = parseArgs(
                    typicalGlobalOptions()
                            + " activate-staking --per-node-amount 100h --staking-reward-rate 50h --reward-account-balance 1000h");

            assertCommandHierarchyOf(shortResult, "yahcli", "activate-staking");
            assertCommandHierarchyOf(longResult, "yahcli", "activate-staking");
        }

        @Test
        void mixedShortAndLongOptionsWork() {
            final var result =
                    parseArgs(typicalGlobalOptions() + " activate-staking -p 100h --staking-reward-rate 50h -b 1000h");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }

        @Test
        void commandWorksWithoutOptionalParameters() {
            final var result = parseArgs(typicalGlobalOptions() + " activate-staking");
            assertCommandHierarchyOf(result, "yahcli", "activate-staking");
        }
    }
}
