// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.schedules;

import static com.hedera.services.yahcli.config.ConfigUtils.configFrom;
import static com.hedera.services.yahcli.util.ParseUtils.normalizePossibleIdLiteral;

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

        final var normalizedScheduleId = normalizePossibleIdLiteral(config, scheduleId);
        final var effectiveScheduleId = normalizedScheduleId != null ? normalizedScheduleId : "";
        var delegate = new ScheduleSuite(config, effectiveScheduleId);
        delegate.runSuiteSync();

        if (delegate.getFinalSpecs().getFirst().getStatus() == HapiSpec.SpecStatus.PASSED) {
            config.output().info("SUCCESS - " + "scheduleId " + effectiveScheduleId + " " + " signed");
        } else {
            config.output().warn("FAILED - " + "could not sign scheduleId " + effectiveScheduleId);
            return 1;
        }

        return 0;
    }
}
