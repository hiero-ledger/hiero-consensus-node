// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.schedules;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(
        name = "schedule",
        subcommands = {HelpCommand.class, SignCommand.class},
        description = "Performs scheduler operations")
public class ScheduleCommand implements Callable<Integer> {
    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws CommandLine.ParameterException {
        throw new CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify a schedule subcommand!");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
