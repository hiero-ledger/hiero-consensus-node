// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.yahcli.test.YahcliTestBase;
import com.hedera.services.yahcli.test.commands.accounts.dsl.GenNewKey;
import com.hedera.services.yahcli.test.commands.accounts.dsl.KeyType;
import com.hedera.services.yahcli.test.commands.accounts.dsl.ReplKey;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

public class RekeyCommandTest extends YahcliTestBase {
    private static final String BASE_COMMAND = " accounts rekey ";

    @Test
    public void basicHelpCommand() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "rekey", "help");
        final var result = execute(typicalGlobalOptions() + BASE_COMMAND + " help");
        assertEquals(0, result);
        assertHasContent(
                "yahcli accounts rekey [-g] [-k=path to new key file] [-K=keyType]\n"
                        + "                             <account> [COMMAND]",
                "Commands:");
    }

    @Test
    public void keyTypeDefaultsToED25519WhenGeneratingNewKey() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "-g 0.0.123");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "rekey");

        final var cmdSpec = findSubcommand(parseResult, "rekey");
        assertThat(cmdSpec).isPresent();
        assertThat(cmdSpec.get().parent().name()).isEqualTo("accounts");

        // Check default values of options
        final var keyTypeOption = cmdSpec.get().findOption(KeyType.LONG_OPT.str());
        assertThat(keyTypeOption.defaultValue()).isEqualTo("ED25519");
        assertEquals("ED25519", keyTypeOption.getValue());
        assertEquals(
                Boolean.TRUE, cmdSpec.get().findOption(GenNewKey.LONG_OPT.str()).getValue());
    }

    @Test
    public void canSpecifyKeyTypeWhenGeneratingNewKey() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + GenNewKey.LONG_OPT.str() + "=true "
                + KeyType.LONG_OPT.str() + "=SECP256K1 0.0.123");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "rekey");

        final var cmdSpec = findSubcommand(parseResult, "rekey");
        assertThat(cmdSpec).isPresent();

        // Verify option values in parse result
        assertEquals(
                "SECP256K1", cmdSpec.get().findOption(KeyType.LONG_OPT.str()).getValue());
        assertEquals(
                Boolean.TRUE, cmdSpec.get().findOption(GenNewKey.LONG_OPT.str()).getValue());
    }

    @Test
    public void keyTypeIsIgnoredWhenUsingExistingKey() {
        final var parseResult =
                parseArgs(typicalGlobalOptions() + BASE_COMMAND + "-k=some/path.pem -K=SECP256K1 0.0.123");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "rekey");

        final var cmdSpec = findSubcommand(parseResult, "rekey");
        assertThat(cmdSpec).isPresent();

        // Verify option values in parse result
        assertEquals(
                Boolean.FALSE,
                cmdSpec.get().findOption(GenNewKey.LONG_OPT.str()).getValue());
        assertEquals(
                "SECP256K1", cmdSpec.get().findOption(KeyType.LONG_OPT.str()).getValue());
        assertEquals(
                "some/path.pem",
                cmdSpec.get().findOption(ReplKey.LONG_OPT.str()).getValue());
    }

    @Test
    public void accountIsRequired() {
        final var exception = org.junit.jupiter.api.Assertions.assertThrows(
                CommandLine.MissingParameterException.class,
                () -> parseArgs(typicalGlobalOptions() + BASE_COMMAND + "-g"));
        assertThat(exception.getMessage()).contains("Missing required parameter: '<account>'");
    }
}
