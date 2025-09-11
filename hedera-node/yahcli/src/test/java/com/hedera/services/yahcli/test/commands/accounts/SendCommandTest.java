// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.services.yahcli.test.YahcliTestBase;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Beneficiary;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Decimals;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Denomination;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public class SendCommandTest extends YahcliTestBase {
    private static final String BASE_COMMAND = " accounts send ";

    @Test
    public void basicHelpCommand() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "send", "help");
        final var result = execute(typicalGlobalOptions() + " accounts send help");
        assertEquals(0, result);
        assertHasContent(
                """
                        yahcli accounts send [--inside-batch] [-d=denomination]
                                                    [--decimals=<decimals>] [--memo=<memo>]
                                                    [--to=<beneficiary>] <amount_to_send> [COMMAND]""",
                "Commands:");
    }

    @Test
    public void canSendHbarsWithDefaultDenomination() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--to=0.0.456 5");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "send");

        final var cmdSpec = findSubcommand(parseResult, "send");
        assertThat(cmdSpec).isPresent();

        // Verify default values and parsed parameters
        assertEquals(
                "hbar", cmdSpec.get().findOption(Denomination.LONG_OPT.str()).getValue());
        assertEquals(
                "0.0.456", cmdSpec.get().findOption(Beneficiary.LONG_OPT.str()).getValue());
        assertEquals("5", cmdSpec.get().positionalParameters().getFirst().getValue());
        assertEquals(Boolean.FALSE, cmdSpec.get().findOption("--inside-batch").getValue());
        assertEquals(6, (Integer) cmdSpec.get().findOption("--decimals").getValue());
    }

    @Test
    public void canSendWithCustomDenomination() {
        final var parseResult =
                parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--to=0.0.456 --denomination=tinybar 1000000");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "send");

        final var cmdSpec = findSubcommand(parseResult, "send");
        assertThat(cmdSpec).isPresent();

        // Verify custom denomination
        assertEquals(
                "tinybar", cmdSpec.get().findOption(Denomination.LONG_OPT.str()).getValue());
        assertEquals(
                "tinybar",
                cmdSpec.get().findOption(Denomination.SHORT_OPT.str()).getValue());
    }

    @Test
    public void canSendWithMemo() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--to=0.0.456 --memo=testMemo 50");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "send");

        final var cmdSpec = findSubcommand(parseResult, "send");
        assertThat(cmdSpec).isPresent();

        // Verify memo is set
        assertEquals("testMemo", cmdSpec.get().findOption("--memo").getValue());
    }

    @Test
    public void canSendTokensWithCustomDecimals() {
        final var parseResult =
                parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--to=0.0.456 -d=0.0.789 --decimals=8 100.25");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "send");

        final var cmdSpec = findSubcommand(parseResult, "send");
        assertThat(cmdSpec).isPresent();

        // Verify token denomination and decimals
        assertEquals(
                "0.0.789", cmdSpec.get().findOption(Denomination.LONG_OPT.str()).getValue());
        assertEquals(
                8, (Integer) cmdSpec.get().findOption(Decimals.LONG_OPT.str()).getValue());
    }

    @Test
    public void canSendInsideBatch() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--to=0.0.456 --inside-batch 50");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "send");

        final var cmdSpec = findSubcommand(parseResult, "send");
        assertThat(cmdSpec).isPresent();

        // Verify inside-batch flag is set
        assertEquals(Boolean.TRUE, cmdSpec.get().findOption("--inside-batch").getValue());
    }

    @Test
    public void failsWhenAmountNotProvided() {
        final var exception = assertThrows(
                CommandLine.MissingParameterException.class,
                () -> parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--to=0.0.456"));
        // Verify error message
        assertThat(exception.getMessage()).contains("Missing required parameter: '<amount_to_send>'");
    }
}
