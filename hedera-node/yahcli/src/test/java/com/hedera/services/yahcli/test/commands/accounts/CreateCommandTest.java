// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.yahcli.test.YahcliTestBase;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Amount;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Denomination;
import com.hedera.services.yahcli.test.commands.accounts.dsl.KeyType;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Memo;
import org.junit.jupiter.api.Test;

public class CreateCommandTest extends YahcliTestBase {
    private static final String BASE_COMMAND = " accounts create ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "create", "help");
        final var result = execute(typicalGlobalOptions() + BASE_COMMAND + " help");
        assertEquals(0, result);
        assertHasContent(" yahcli accounts create", "[COMMAND]", "Commands:");
    }

    @Test
    public void defaultOptionsAreSet() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND);
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "create");

        final var cmdSpec = findSubcommand(parseResult, "create");
        assertThat(cmdSpec).isPresent();

        assertEquals(
                "hbar", cmdSpec.get().findOption(Denomination.LONG_OPT.str()).getValue());
        assertEquals("ED25519", cmdSpec.get().findOption(KeyType.LONG_OPT.str()).getValue());
        assertEquals("0", cmdSpec.get().findOption(Amount.LONG_OPT.str()).getValue());
        assertEquals(
                Boolean.FALSE, cmdSpec.get().findOption("--receiverSigRequired").getValue());
    }

    @Test
    public void canSetCustomMemo() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--memo=testMemo");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "create");

        final var cmdSpec = findSubcommand(parseResult, "create");
        assertThat(cmdSpec).isPresent();

        assertEquals("testMemo", cmdSpec.get().findOption(Memo.LONG_OPT.str()).getValue());
    }

    @Test
    public void canSetKeyTypeSecp256k1() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--keyType=SECP256K1");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "create");

        final var cmdSpec = findSubcommand(parseResult, "create");
        assertThat(cmdSpec).isPresent();

        assertEquals(
                "SECP256K1", cmdSpec.get().findOption(KeyType.LONG_OPT.str()).getValue());
    }

    @Test
    public void canSetInitialBalance() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--amount=1000");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "create");

        final var cmdSpec = findSubcommand(parseResult, "create");
        assertThat(cmdSpec).isPresent();

        assertEquals("1000", cmdSpec.get().findOption("--amount").getValue());
    }

    @Test
    public void canSetReceiverSigRequired() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--receiverSigRequired");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "create");

        final var cmdSpec = findSubcommand(parseResult, "create");
        assertThat(cmdSpec).isPresent();

        assertEquals(
                Boolean.TRUE, cmdSpec.get().findOption("--receiverSigRequired").getValue());
    }
}
