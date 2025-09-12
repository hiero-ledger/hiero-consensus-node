// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.test.commands.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.hedera.services.yahcli.test.YahcliTestBase;
import org.junit.jupiter.api.Test;

public class PrepareUpgradeCommandTest extends YahcliTestBase {
    private static final String COMMAND = " prepare-upgrade ";

    @Test
    public void basicHelpWorks() {
        final var parseResult = parseArgs(typicalGlobalOptions() + COMMAND + "help");
        assertCommandHierarchyOf(parseResult, "yahcli", "prepare-upgrade", "help");
        final var result = execute(typicalGlobalOptions() + COMMAND + "help");
        assertEquals(0, result);
        assertHasContent(
                """
                Usage: yahcli prepare-upgrade [-f=Number of the upgrade ZIP file]
                                              [-h=Hex-encoded SHA-384 hash of the upgrade ZIP]
                                              [COMMAND]""",
                "Commands:");
    }

    @Test
    public void prepareUpgradeParses() {
        final var result = parseArgs(typicalGlobalOptions() + COMMAND + "-f 150 -h abcd1234");
        final var cmdSpec = findSubcommand(result, "prepare-upgrade");
        assertThat(cmdSpec).isPresent();
        assertThat(cmdSpec.get().parent().name()).isEqualTo("yahcli");
        assertEquals("150", cmdSpec.get().findOption("-f").getValue());
        assertEquals("abcd1234", cmdSpec.get().findOption("-h").getValue());
    }

    @Test
    public void prepareUpgradeWithAdditionalArgsFails() {
        stopOnUnmatched(true);
        final var result = parseArgs(typicalGlobalOptions() + COMMAND + " unexpected");
        final var subcommandResult = findSubcommandResult(result, "prepare-upgrade");
        assertThat(subcommandResult).isPresent();
        assertThat(subcommandResult.get().unmatched()).isNotEmpty();
        assertThat(subcommandResult.get().unmatched()).size().isEqualTo(2);
    }

    @Test
    public void prepareUpgradeWithNoOptionsParsesAndHasDefaultValue() {
        final var parseResult = parseArgs(typicalGlobalOptions() + COMMAND);
        final var cmdSpec = findSubcommand(parseResult, "prepare-upgrade");
        assertThat(cmdSpec).isPresent();
        // -f has default value "150", -h should be null
        assertEquals("150", cmdSpec.get().findOption("-f").getValue());
        assertNull(cmdSpec.get().findOption("-h").getValue());
    }
}
