// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.system;

import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.Yahcli;
import com.hedera.services.yahcli.config.ConfigUtils;
import com.hedera.services.yahcli.suites.FreezeHelperSuite;
import com.hedera.services.yahcli.suites.Utils;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "freeze",
        subcommands = {picocli.CommandLine.HelpCommand.class},
        description = "Schedules a freeze for network maintenance (no NMT upgrade)")
public class FreezeOnlyCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    private Yahcli yahcli;

    @CommandLine.Option(
            names = {"-s", "--start-time"},
            paramLabel = "Freeze start time in UTC (yyyy-MM-dd.HH:mm:ss)")
    private String startTime;

    @Override
    public Integer call() throws Exception {

        final var config = ConfigUtils.configFrom(yahcli);

        final var freezeStartTime = Utils.parseFormattedInstant(startTime);
        final var delegate = new FreezeHelperSuite(config, freezeStartTime, false);

        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - " + "freeze scheduled for " + startTime);
        } else {
            COMMON_MESSAGES.warn("FAILED - freeze is not scheduled for " + startTime);
            return 1;
        }

        return 0;
    }
}
