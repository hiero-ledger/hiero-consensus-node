// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.contracts;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(
        name = "contracts",
        subcommands = {
            HelpCommand.class,
            CreateCommand.class,
            CallCommand.class,
        },
        description = "Performs smart contract operations against a Hedera Services network")
public class ContractsCommand implements Callable<Integer> {
    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws CommandLine.ParameterException {
        throw new CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify a contracts subcommand!");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
