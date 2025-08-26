package com.hedera.services.yahcli.commands.ivy;

import com.hedera.services.yahcli.Yahcli;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "accounts",
        subcommands = {
                CommandLine.HelpCommand.class,
        },
        description = "Performs account operations")
public class IvyCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify an accounts subcommand!");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
