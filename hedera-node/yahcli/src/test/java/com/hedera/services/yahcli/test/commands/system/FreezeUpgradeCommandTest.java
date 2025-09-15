// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.services.yahcli.test.YahcliTestBase;
import org.junit.jupiter.api.Test;

public class FreezeUpgradeCommandTest extends YahcliTestBase {
    private static final String COMMAND = " freeze-upgrade ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "freeze-upgrade", "help");
        final var result = execute(typicalGlobalOptions() + COMMAND + "help");
        assertEquals(0, result);
        assertHasContent(
                """
                Usage: yahcli freeze-upgrade [-f=Number of the upgrade ZIP file]
                                             [-h=Hex-encoded SHA-384 hash of the upgrade ZIP]
                                             [-s=Upgrade start time in UTC (yyyy-MM-dd.HH:mm:
                                             ss)] [COMMAND]""",
                "Commands:");
    }

    @Test
    public void freezeUpgradeParses() {
        final var result = parseArgs(typicalGlobalOptions() + COMMAND + "-f 150 -h abcd1234 -s 2024-07-01.12:00:00");
        final var cmdSpec = findSubcommand(result, "freeze-upgrade");
        assertThat(cmdSpec).isPresent();
        assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
        assertEquals("150", cmdSpec.get().findOption("-f").getValue());
        assertEquals("abcd1234", cmdSpec.get().findOption("-h").getValue());
        assertEquals("2024-07-01.12:00:00", cmdSpec.get().findOption("-s").getValue());
    }

    @Test
    public void freezeUpgradeWithNoOptionsParsesAndHasDefaultValues() {
        final var parseResult = parseArgs(typicalGlobalOptions() + COMMAND);
        final var cmdSpec = findSubcommand(parseResult, "freeze-upgrade");
        assertThat(cmdSpec).isPresent();
        // -f has default value "150", others should be null
        assertEquals("150", cmdSpec.get().findOption("-f").getValue());
        assertNull(cmdSpec.get().findOption("-h").getValue());
        assertNull(cmdSpec.get().findOption("-s").getValue());
    }

    @Test
    public void freezeUpgradeWithAdditionalArgsFails() {
        stopOnUnmatched(true);
        final var result = parseArgs(typicalGlobalOptions() + COMMAND + " unexpected");
        final var subcommandResult = findSubcommandResult(result, "freeze-upgrade");
        assertThat(subcommandResult).isPresent();
        assertThat(subcommandResult.get().unmatched()).isNotEmpty();
        assertThat(subcommandResult.get().unmatched()).size().isEqualTo(2);
    }
}
