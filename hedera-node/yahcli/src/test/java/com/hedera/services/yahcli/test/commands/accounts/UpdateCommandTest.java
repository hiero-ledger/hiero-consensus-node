// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.services.yahcli.test.YahcliTestBase;
import com.hedera.services.yahcli.test.commands.accounts.dsl.Memo;
import org.junit.jupiter.api.Test;

public class UpdateCommandTest extends YahcliTestBase {
    private static final String BASE_COMMAND = " accounts update ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "update", "help");
        final var result = execute(typicalGlobalOptions() + BASE_COMMAND + " help");
        assertEquals(0, result);
        assertHasContent(
                "yahcli accounts update [--memo=<memo>] [--pathKeys=<pathKeys>]\n"
                        + "                              [--targetAccount=<targetAccount>] [COMMAND]",
                "Commands:");
    }

    @Test
    public void parsesMemoAndPathKeysAndTargetAccount() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND
                + "--memo=testMemo --pathKeys=/tmp/keys.txt --targetAccount=0.0.1234");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "update");
        final var cmdSpec = findSubcommand(parseResult, "update");
        assertThat(cmdSpec).isPresent();
        assertEquals("testMemo", cmdSpec.get().findOption(Memo.LONG_OPT.str()).getValue());
        assertEquals("/tmp/keys.txt", cmdSpec.get().findOption("--pathKeys").getValue());
        assertEquals("0.0.1234", cmdSpec.get().findOption("--targetAccount").getValue());
    }

    @Test
    public void parsesWithNoOptions() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND);
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "update");
        final var cmdSpec = findSubcommand(parseResult, "update");
        assertThat(cmdSpec).isPresent();
        assertNull(cmdSpec.get().findOption(Memo.LONG_OPT.str()).getValue());
        assertNull(cmdSpec.get().findOption("--pathKeys").getValue());
        assertNull(cmdSpec.get().findOption("--targetAccount").getValue());
    }
}
