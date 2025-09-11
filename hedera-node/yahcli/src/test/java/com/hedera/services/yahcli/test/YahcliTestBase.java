// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.test.util.DualPrintStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import picocli.CommandLine;

public class YahcliTestBase {
    public static final String REGRESSION = "REGRESSION";

    // (FUTURE) Wrap System.out and System.err to capture _and display_ outputs
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;

    private Yahcli yahcli;
    private CommandLine commandLine;

    @BeforeEach
    void setUp() {
        // Reset the test output streams
        outContent.reset();
        errContent.reset();

        // Modify the output streams to capture System.out and System.err
        System.setOut(new DualPrintStream(outContent, originalOut));
        System.setErr(new DualPrintStream(errContent, originalErr));

        // Instantiate the `Yahcli` and `CommandLine` instances
        yahcli = new Yahcli();
        commandLine = new CommandLine(yahcli);
    }

    @AfterEach
    void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    protected Yahcli testSubjectCli() {
        return yahcli;
    }

    protected CommandLine.ParseResult parseArgs(final String... args) {
        return commandLine.parseArgs(args);
    }

    protected CommandLine.ParseResult parseArgs(final String args) {
        List<String> result = new ArrayList<>();
        // Match and preserve quoted strings while splitting by whitespace outside of quotes
        Pattern pattern = Pattern.compile("\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|\\S+");
        Matcher matcher = pattern.matcher(args);

        while (matcher.find()) {
            String match = matcher.group();
            // Remove surrounding quotes if present
            if ((match.startsWith("\"") && match.endsWith("\"")) || (match.startsWith("'") && match.endsWith("'"))) {
                match = match.substring(1, match.length() - 1);
            }
            result.add(match);
        }

        return commandLine.parseArgs(result.toArray(new String[0]));
    }

    protected int execute(final String... args) {
        return commandLine.execute(args);
    }

    protected int execute(final String args) {
        return commandLine.execute(args.split(" "));
    }

    protected CommandLine testSubjectCL() {
        return commandLine;
    }

    protected static String typicalGlobalOptions() {
        return "-c config.yml -n testnet -a 3 -p 2";
    }

    protected void assertHasContent(final String expectedContent) {
        String output = outContent.toString();
        assertThat(output).contains(expectedContent);
    }

    protected void assertHasContent(final String... expectedContents) {
        for (String expectedContent : expectedContents) {
            assertHasContent(expectedContent);
        }
    }

    protected Optional<CommandLine.Model.OptionSpec> findOption(
            final CommandLine.ParseResult parseResult, final String cmdName, final String optionName) {
        if (parseResult == null || parseResult.commandSpec() == null) {
            throw new IllegalArgumentException("Parse result or command spec is null");
        }

        final var subCmd = findSubcommand(parseResult, cmdName);
        return subCmd.map(cs -> cs.findOption(optionName));
    }

    protected Optional<CommandLine.Model.CommandSpec> findSubcommand(
            final CommandLine.ParseResult parseResult, final String commandName) {
        if (parseResult == null || parseResult.subcommand() == null) {
            throw new IllegalArgumentException("Parse result or subcommand is null");
        }

        CommandLine.ParseResult subCmdResult = parseResult.subcommand();
        while (subCmdResult != null) {
            var subCmdSpec = subCmdResult.commandSpec();
            if (subCmdSpec.name().equals(commandName)) {
                return Optional.of(subCmdSpec);
            } else {
                subCmdResult = subCmdResult.subcommand();
            }
        }

        return Optional.empty();
    }

    protected void assertHasErrorContent(final String expectedContent) {
        final String errorOutput = errContent.toString();
        assertThat(errorOutput).contains(expectedContent);
    }

    protected void assertCommandHierarchyOf(final CommandLine.ParseResult result, String... commandNames) {
        // Verify root command (different API from subcommands)
        assertThat(result.commandSpec().name()).isEqualTo(commandNames[0]);

        // Verify all subcommands
        var subCmd = result.subcommand();
        // This counter starts at 1 because the root command is already counted
        int commandCount = 1;
        for (int i = 1; i < commandNames.length; i++) {
            if (subCmd == null) {
                fail("Expected subcommand at index " + i + " but found none");
                break;
            }

            assertThat(subCmd.commandSpec().name()).isEqualTo(commandNames[i]);
            commandCount++;

            subCmd = subCmd.subcommand();
        }
        assertThat(commandCount).isEqualTo(commandNames.length);
    }

    protected void assertOptionNameIsCaseSensitive(final String shortName, final String longName, String... args) {
        final var allArgs =
                Stream.concat(Stream.of(longName), Arrays.stream(args)).collect(Collectors.joining(" "));
        // The `allArgs` concatenation above is done merely to invoke `parseArgs(longName, args[0], args[1], ...)`
        final CommandLine.ParseResult result = parseArgs(allArgs);
        // Verify that the short option is recognized by comparing its 'longest name' to the actual `longName` option
        assertThat(result.matchedOption(shortName).longestName()).isEqualTo(longName);

        final var longNameCapsInvalid = longName.toUpperCase();
        // Similarly, `allCapsInvalidArgs` is constructed in order to invoke `parseArgs(longNameCapsInvalid, args[0],
        // args[1], ...)`
        final var allCapsInvalidArgs = Stream.concat(Stream.of(longNameCapsInvalid), Arrays.stream(args))
                .collect(Collectors.joining(" "));
        final CommandLine.ParameterException exception =
                assertThrows(CommandLine.ParameterException.class, () -> parseArgs(allCapsInvalidArgs));
        ExceptionMsgUtils.assertUnknownOptionMsg(exception, longNameCapsInvalid);
    }
}
