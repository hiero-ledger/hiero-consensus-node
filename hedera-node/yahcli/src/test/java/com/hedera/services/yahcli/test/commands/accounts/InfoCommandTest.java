// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

public class InfoCommandTest extends YahcliTestBase {
    private static final String BASE_COMMAND = " accounts info";

    private static final String[] VALID_ACCOUNTS =
            new String[] {"0.0.1234 0.0.5678 alias1 alias2", "0.0.1234 0.0.5678", "0.0.1234", "alias1 alias2", "alias1"
            };

    private static Stream<Arguments> validAccounts() {
        return Stream.of(VALID_ACCOUNTS).map(Arguments::of);
    }

    @Test
    public void basicHelpCommand() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + " help");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "info", "help");
        final var result = execute(typicalGlobalOptions() + BASE_COMMAND + " help");
        assertEquals(0, result);
        assertHasContent("yahcli accounts info <accounts>... [COMMAND]", "Commands:");
    }

    @Test
    public void testWithNoAccountFails() {
        final var exception = assertThrows(
                CommandLine.MissingParameterException.class, () -> parseArgs(typicalGlobalOptions() + BASE_COMMAND));
        assertThat(exception.getMessage()).contains("Missing required parameter: '<accounts>'");
    }

    @ParameterizedTest
    @MethodSource("com.hedera.services.yahcli.test.commands.accounts.InfoCommandTest#validAccounts")
    public void testCallWithValidAccounts(final String accounts) {
        final var result = parseArgs(typicalGlobalOptions() + BASE_COMMAND + " " + accounts);
        assertCommandHierarchyOf(result, "yahcli", "accounts", "info");
    }
}
