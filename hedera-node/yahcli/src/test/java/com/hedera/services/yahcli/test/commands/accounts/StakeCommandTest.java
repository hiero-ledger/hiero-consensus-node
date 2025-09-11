// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.yahcli.test.YahcliTestBase;
import org.junit.jupiter.api.Test;

public class StakeCommandTest extends YahcliTestBase {
    private static final String BASE_COMMAND = " accounts stake ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "stake", "help");
        final var result = execute(typicalGlobalOptions() + BASE_COMMAND + " help");
        assertEquals(0, result);
        assertHasContent(
                """
                yahcli accounts stake [--start-declining-rewards]
                                             [--stop-declining-rewards] [-a=the account to
                                             stake to] [-n=id of node to stake to] [<account>]
                                             [COMMAND]""",
                "Commands:");
    }

    @Test
    public void parsesStakeToNode() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "-n 3 0.0.1001");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "stake");
        final var cmdSpec = findSubcommand(parseResult, "stake");
        assertThat(cmdSpec).isPresent();
        assertEquals("3", cmdSpec.get().findOption("-n").getValue());
        assertEquals("0.0.1001", cmdSpec.get().positionalParameters().getFirst().getValue());
    }

    @Test
    public void parsesStakeToAccount() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "-a 0.0.2002 0.0.1001");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "stake");
        final var cmdSpec = findSubcommand(parseResult, "stake");
        assertThat(cmdSpec).isPresent();
        assertEquals("0.0.2002", cmdSpec.get().findOption("-a").getValue());
        assertEquals("0.0.1001", cmdSpec.get().positionalParameters().getFirst().getValue());
    }

    @Test
    public void parsesStartDecliningRewards() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--start-declining-rewards 0.0.1001");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "stake");
        final var cmdSpec = findSubcommand(parseResult, "stake");
        assertThat(cmdSpec).isPresent();
        assertEquals(Boolean.TRUE, cmdSpec.get().findOption("--start-declining-rewards").getValue());
        assertEquals("0.0.1001", cmdSpec.get().positionalParameters().getFirst().getValue());
    }

    @Test
    public void parsesStopDecliningRewards() {
        final var parseResult = parseArgs(typicalGlobalOptions() + BASE_COMMAND + "--stop-declining-rewards 0.0.1001");
        assertCommandHierarchyOf(parseResult, "yahcli", "accounts", "stake");
        final var cmdSpec = findSubcommand(parseResult, "stake");
        assertThat(cmdSpec).isPresent();
        assertEquals(Boolean.TRUE, cmdSpec.get().findOption("--stop-declining-rewards").getValue());
        assertEquals("0.0.1001", cmdSpec.get().positionalParameters().getFirst().getValue());
    }
}
