// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.yahcli.test.YahcliTestBase;
import org.junit.jupiter.api.Test;

public class FreezeAbortCommandTest extends YahcliTestBase {
    private static final String COMMAND = " freeze-abort ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "freeze-abort", "help");
        final var result = execute(typicalGlobalOptions() + COMMAND + "help");
        assertEquals(0, result);
        assertHasContent("Usage: yahcli freeze-abort [COMMAND]", "Commands:");
    }

    @Test
    public void freezeAbortParses() {
        final var result = parseArgs(typicalGlobalOptions() + COMMAND);
        final var cmdSpec = findSubcommand(result, "freeze-abort");
        assertThat(cmdSpec).isPresent();
        assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
    }

    @Test
    public void freezeAbortWithAdditionalArgsFails() {
        try {
            stopOnUnmatched(true);
            final var result = parseArgs(typicalGlobalOptions() + COMMAND + " unexpected");
            final var subcommandResult = findSubcommandResult(result, "freeze-abort");
            assertThat(subcommandResult).isPresent();
            assertThat(subcommandResult.get().unmatched()).isNotEmpty();
            assertThat(subcommandResult.get().unmatched()).size().isEqualTo(2);
        } finally {
            stopOnUnmatched(false);
        }
    }
}
