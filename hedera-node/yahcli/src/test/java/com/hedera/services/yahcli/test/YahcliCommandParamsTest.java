// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.yahcli.test.dsl.Config;
import com.hedera.services.yahcli.test.dsl.FixedFee;
import com.hedera.services.yahcli.test.dsl.LogLevel;
import com.hedera.services.yahcli.test.dsl.Network;
import com.hedera.services.yahcli.test.dsl.NodeAccount;
import com.hedera.services.yahcli.test.dsl.NodeIp;
import com.hedera.services.yahcli.test.dsl.Payer;
import com.hedera.services.yahcli.test.dsl.Schedule;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.ParameterException;

public class YahcliCommandParamsTest extends YahcliTestBase {

    private static final List<String> EXPECTED_SUBCOMMANDS = List.of(
            "accounts",
            "activate-staking",
            "fees",
            "freeze",
            "freeze-abort",
            "freeze-upgrade",
            "help",
            "ivy",
            "keys",
            "nodes",
            "schedule",
            "sysfiles",
            "prepare-upgrade",
            "upgrade-telemetry",
            "version");

    @Nested
    class FixedFeeTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(FixedFee.SHORT_OPT.str(), FixedFee.LONG_OPT.str(), "100");
        }

        @Test
        void testExtremelyLargeFixedFee() {
            parseArgs(FixedFee.SHORT_OPT.str(), String.valueOf(Long.MAX_VALUE));
            assertThat(testSubjectCli().getFixedFee()).isEqualTo(Long.MAX_VALUE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-a-number", "123abc", "12.34", "1.23e10", "1,000", "1_000", "12.34.555.6666"})
        void nonNumericFeeThrowsException(String invalidFeeValue) {
            ParameterException exception =
                    assertThrows(ParameterException.class, () -> parseArgs(FixedFee.SHORT_OPT.str(), invalidFeeValue));
            assertThat(exception.getValue()).isEqualTo(invalidFeeValue);
        }
    }

    @Nested
    class NetworkOptionTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(Network.SHORT_OPT.str(), Network.LONG_OPT.str(), "testnet");
        }

        @ParameterizedTest
        @ValueSource(strings = {"testnet", "mainNet", "PReviewnet"})
        void optionValueIsCaseSensitive(final String networkName) {
            parseArgs(Network.SHORT_OPT.str(), networkName);
            assertThat(testSubjectCli().getNet()).isEqualTo(networkName);
        }
    }

    @Nested
    class NodeAccountOptionTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(NodeAccount.SHORT_OPT.str(), NodeAccount.LONG_OPT.str(), "3");
        }

        @Test
        void nodeAccountParamAcceptsValidAccountId() {
            parseArgs(NodeAccount.SHORT_OPT.str(), "1234");
            assertThat(testSubjectCli().getNodeAccount()).isEqualTo("1234");
        }

        @Test
        @Disabled("FUTURE: Add validation for account ID format")
        void nodeAccountParamRejectsInvalidAccountId() {
            assertInvalidAccountOptions(NodeAccount.SHORT_OPT.str());
            assertInvalidAccountOptions(NodeAccount.LONG_OPT.str());
        }
    }

    @Nested
    class NodeIpOptionTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(NodeIp.SHORT_OPT.str(), NodeIp.LONG_OPT.str(), "127.0.0.1");
        }

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1", "0.0.0.0.", "255.255.255.255"})
        void acceptsValidIpv4Address(final String ipAddress) {
            parseArgs(NodeIp.SHORT_OPT.str(), ipAddress);
            assertThat(testSubjectCli().getNodeIpv4Addr()).isEqualTo(ipAddress);
        }

        @ParameterizedTest
        @ValueSource(strings = {"::1", "2001:0db8:85a3:0000:0000:8a2e:0370:7334", "fe80::1ff:fe23:4567:890a"})
        @Disabled("FUTURE: Add validation for IP address format")
        void rejectsIpv6Address(final String ipAddress) {
            final ParameterException exception =
                    assertThrows(ParameterException.class, () -> parseArgs(NodeIp.SHORT_OPT.str(), ipAddress));
            ExceptionMsgUtils.assertInvalidOptionMsg(exception, ipAddress);
        }
    }

    @Nested
    class PayerOptionTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(Payer.SHORT_OPT.str(), Payer.LONG_OPT.str(), "3");
        }

        @Test
        void payerOptionAcceptsValidAccountId() {
            parseArgs(Payer.SHORT_OPT.str(), "1234");
            assertThat(testSubjectCli().getPayer()).isEqualTo("1234");
        }

        @Test
        @Disabled("FUTURE: Add validation for account ID format")
        void payerOptionRejectsInvalidAccountId() {
            assertInvalidAccountOptions(Payer.SHORT_OPT.str());
            assertInvalidAccountOptions(Payer.LONG_OPT.str());
        }
    }

    @Nested
    class ScheduleOptionTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(Payer.SHORT_OPT.str(), Payer.LONG_OPT.str(), "3");
        }

        @Test
        void optionPresentIndicatesScheduledExecution() {
            parseArgs(Schedule.SHORT_OPT.str());
            assertThat(testSubjectCli().isScheduled()).isTrue();

            parseArgs();
            assertThat(testSubjectCli().isScheduled()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    // Boolean word as a value
                    "true",
                    // Numeric value
                    "0"
                })
        void optionDoesNotAcceptValue(final String value) {
            CommandLine.UnmatchedArgumentException result = assertThrows(
                    CommandLine.UnmatchedArgumentException.class, () -> parseArgs(Schedule.SHORT_OPT.str(), value));
            assertUnmatchedArgumentMsg(result, value);
        }

        private static void assertUnmatchedArgumentMsg(
                final CommandLine.UnmatchedArgumentException exception, final String unmatchedArg) {
            assertThat(exception.getMessage())
                    .containsIgnoringCase("Unmatched argument")
                    .contains("'" + unmatchedArg + "'");
        }
    }

    @Nested
    class ConfigOptionTests {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(Config.SHORT_OPT.str(), Config.LONG_OPT.str(), "custom-config.yml");
        }

        @ParameterizedTest
        @ValueSource(strings = {"custom-CONFIG.yml", "configs/another-config.yml", "/home/user/config.json"})
        void optionValueIsCaseSensitive(final String configFile) {
            parseArgs(Config.SHORT_OPT.str(), configFile);
            assertThat(testSubjectCli().getConfigLoc()).isEqualTo(configFile);
        }
    }

    @Nested
    class LogLevelOptionTest {
        @Test
        void optionNameIsCaseSensitive() {
            assertOptionNameIsCaseSensitive(LogLevel.SHORT_OPT.str(), LogLevel.LONG_OPT.str(), "WARN");
        }

        @ParameterizedTest
        @ValueSource(strings = {"warn", "WARN", "Warn", "wArN"})
        void optionValueIsCaseSensitive(String logLevel) {
            parseArgs(LogLevel.SHORT_OPT.str(), logLevel);
            assertThat(testSubjectCli().getLogLevel()).isEqualTo(Level.WARN);

            parseArgs(LogLevel.LONG_OPT.str(), logLevel);
            assertThat(testSubjectCli().getLogLevel()).isEqualTo(Level.WARN);
        }

        @ParameterizedTest
        @ValueSource(strings = {"WARN", "INFO", "DEBUG", "ERROR", "TRACE"})
        void specifiedLogLevelReturnsAppropriateLevel(String logLevel) {
            parseArgs(LogLevel.SHORT_OPT.str(), logLevel);
            assertThat(testSubjectCli().getLogLevel()).isEqualTo(Level.getLevel(logLevel));
        }

        @Test
        void invalidLogLevelReturnsWarn() {
            parseArgs(LogLevel.SHORT_OPT.str(), "invalid-level");
            assertThat(testSubjectCli().getLogLevel()).isEqualTo(Level.WARN);
        }
    }

    @Nested
    class UnknownOptionTests {
        @ParameterizedTest
        @ValueSource(strings = {"-0", "-z", /*"-abc",*/ "--def", "ghi=", "--jkl=123", "-mno=456", "--unknown-option"})
        @Disabled("(FUTURE) Also throw exception for invalid values similar to the form '-abc'")
        void unknownOptionThrowsException(String inputParam) {
            ParameterException exception = assertThrows(ParameterException.class, () -> parseArgs(inputParam));
            assertThat(exception.getMessage())
                    .satisfiesAnyOf(
                            (String msg) -> assertThat(msg).contains("Unknown option: '" + inputParam + "'"),
                            (String msg) -> assertThat(msg)
                                    .contains("Unmatched argument")
                                    .contains("'" + inputParam + "'"));
        }

        @Test
        void reportsAllUnknownOptions() {
            // Neither `-X` nor `--unknown-option` are valid options
            final ParameterException exception = assertThrows(
                    ParameterException.class,
                    () -> parseArgs(FixedFee.SHORT_OPT.str(), "100", "-X", "--unknown-option"));
            assertThat(exception.getMessage())
                    .doesNotContain(FixedFee.SHORT_OPT.str())
                    .doesNotContain("100")
                    .containsIgnoringCase("unknown option")
                    .contains("'-X'")
                    .contains("--unknown-option");
        }

        @Test
        void recognizesMixedValidAndInvalidOptions() {
            final Exception exception = assertThrows(
                    ParameterException.class,
                    () -> parseArgs(
                            Network.SHORT_OPT.str(), "testnet", "--invalid-option", Payer.SHORT_OPT.str(), "50"));
            assertTrue(exception.getMessage().contains("Unknown option"), "Error should be about the unknown option");
        }

        @Test
        void noGlobalHelpOption() {
            ParameterException exception = assertThrows(ParameterException.class, () -> parseArgs("-h"));
            ExceptionMsgUtils.assertUnknownOptionMsg(exception, "-h");
            exception = assertThrows(ParameterException.class, () -> parseArgs("--help"));
            ExceptionMsgUtils.assertUnknownOptionMsg(exception, "--help");
        }
    }

    @Nested
    class MiscTests {
        @Test
        void unparsedArgsMakesCommandLineInvalid() {
            // Calling `execute()` without parsing args first should fail
            final int exitCode = testSubjectCL().execute();
            assertThat(exitCode).isNotEqualTo(0);
        }

        @Test
        void verifyUnspecifiedOptionDefaults() {
            // Given an empty args string, yahcli should initialize with its default values for each option
            parseArgs();

            assertThat(testSubjectCli().getLogLevel()).isEqualTo(Level.WARN);
            assertThat(testSubjectCli().getFixedFee()).isEqualTo(Long.MIN_VALUE);
            assertThat(testSubjectCli().getNet()).isNull();
            assertThat(testSubjectCli().getConfigLoc()).isEqualTo("config.yml");
            assertThat(testSubjectCli().getPayer()).isNull();
            assertThat(testSubjectCli().getNodeAccount()).isNull();
            assertThat(testSubjectCli().isScheduled()).isFalse();

            assertEquals(Level.WARN, testSubjectCli().getLogLevel(), "Default log level should be WARN");

            final int exitCode = testSubjectCL().execute();
            // Executing with all default params should fail
            assertThat(exitCode).isNotEqualTo(0);
        }

        @Test
        void multipleNetworksSpecifiedAppliesFirstOption() {
            final CommandLine.OverwrittenOptionException exception = assertThrows(
                    CommandLine.OverwrittenOptionException.class,
                    () -> parseArgs(Network.SHORT_OPT.str(), "testnet", Network.SHORT_OPT.str(), "mainnet"));
            // Verify the exception is due to the `network` option
            assertThat(exception.getOverwritten().paramLabel()).isEqualTo("network");
            assertThat(exception.getMessage()).contains("--network").contains("should be specified only once");
            // Verify that the first specified network is applied
            assertThat(testSubjectCli().getNet()).isEqualTo("testnet");
        }

        @Test
        void registersAllSubcommands() {
            assertThat(testSubjectCL().getSubcommands()).hasSize(EXPECTED_SUBCOMMANDS.size());
            assertThat(testSubjectCL().getSubcommands().keySet()).containsAll(EXPECTED_SUBCOMMANDS);
        }

        @Test
        void callInvocationPriorToParseArgsThrowsException() {
            // Directly invoking `.call()` on the instance before parsing args should fail
            final ParameterException exception = assertThrows(
                    ParameterException.class, () -> testSubjectCli().call());
            assertThat(exception.getMessage()).contains("Please specify a subcommand");
        }
    }

    private void assertInvalidAccountOptions(final String option) {
        ParameterException exception =
                assertThrows(ParameterException.class, () -> parseArgs(option, "invalid-account"));
        ExceptionMsgUtils.assertInvalidOptionMsg(exception, "invalid-account");

        exception = assertThrows(ParameterException.class, () -> parseArgs(option, String.valueOf(Long.MAX_VALUE)));
        ExceptionMsgUtils.assertInvalidOptionMsg(exception, String.valueOf(Long.MAX_VALUE));

        exception = assertThrows(ParameterException.class, () -> parseArgs(option, "0.0.3"));
        ExceptionMsgUtils.assertInvalidOptionMsg(exception, "0.0.3");

        exception = assertThrows(ParameterException.class, () -> parseArgs(option, "0.0.-3"));
        ExceptionMsgUtils.assertInvalidOptionMsg(exception, "0.0.-3");

        exception = assertThrows(ParameterException.class, () -> parseArgs(option, "0.0.1234.5678"));
        ExceptionMsgUtils.assertInvalidOptionMsg(exception, "0.0.1234.5678");

        exception = assertThrows(ParameterException.class, () -> parseArgs(option, "0.0.3-abc"));
        ExceptionMsgUtils.assertInvalidOptionMsg(exception, "0.0.3-abc");
    }
}
