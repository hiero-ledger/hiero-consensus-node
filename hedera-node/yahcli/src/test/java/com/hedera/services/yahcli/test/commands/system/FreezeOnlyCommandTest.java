// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.services.yahcli.test.YahcliTestBase;
import org.junit.jupiter.api.Test;

public class FreezeOnlyCommandTest extends YahcliTestBase {
    private static final String COMMAND = " freeze ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "freeze", "help");
        final var result = execute(typicalGlobalOptions() + COMMAND + "help");
        assertEquals(0, result);
        assertHasContent(
                "Usage: yahcli freeze [-s=Freeze start time in UTC (yyyy-MM-dd.HH:mm:ss)]\n"
                        + "                     [COMMAND]",
                "Commands:");
    }

    @Test
    public void freezeOnlyParses() {
        final var result = parseArgs(typicalGlobalOptions() + COMMAND + "-s 2024-07-01.12:00:00");
        final var cmdSpec = findSubcommand(result, "freeze");
        assertThat(cmdSpec).isPresent();
        assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
        assertEquals("2024-07-01.12:00:00", cmdSpec.get().findOption("-s").getValue());
    }

    @Test
    public void freezeOnlyWithoutStartTime() {
        final var result = parseArgs(typicalGlobalOptions() + COMMAND);
        final var cmdSpec = findSubcommand(result, "freeze");
        assertThat(cmdSpec).isPresent();
        assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
        assertNull(cmdSpec.get().findOption("-s").getValue());
    }

    @Test
    public void freezeOnlyWithAdditionalArgsFails() {
        try {
            stopOnUnmatched(true);
            final var result = parseArgs(typicalGlobalOptions() + COMMAND + " unexpected");
            final var subcommandResult = findSubcommandResult(result, "freeze");
            assertThat(subcommandResult).isPresent();
            assertThat(subcommandResult.get().unmatched()).isNotEmpty();
            assertThat(subcommandResult.get().unmatched()).size().isEqualTo(2);
        } finally {
            stopOnUnmatched(false);
        }
    }
}
