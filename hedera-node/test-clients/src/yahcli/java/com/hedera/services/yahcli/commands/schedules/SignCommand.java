// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.schedules;

import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;
import static com.hedera.services.yahcli.output.CommonMessages.COMMON_MESSAGES;

import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.yahcli.suites.ScheduleSuite;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

@Command(
        name = "sign",
        subcommands = {HelpCommand.class},
        description = "Sign a transaction with schedule id")
public class SignCommand implements Callable<Integer> {

    @ParentCommand
    ScheduleCommand scheduleCommand;

    @CommandLine.Option(
            names = {"--scheduleId"},
            paramLabel = "<scheduleId>",
            description = "schedule Id to sign")
    String scheduleId;

    @Override
    public Integer call() throws Exception {
        var config = configFrom(scheduleCommand.getYahcli());

        final var effectiveScheduleId = scheduleId != null ? scheduleId : "";
        var delegate = new ScheduleSuite(config, effectiveScheduleId);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            COMMON_MESSAGES.info("SUCCESS - " + "scheduleId " + effectiveScheduleId + " " + " signed");
        } else {
            COMMON_MESSAGES.warn("FAILED - " + "could not sign scheduleId " + effectiveScheduleId);
            return 1;
        }

        return 0;
    }
}
