// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.accounts;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(
        name = "accounts",
        subcommands = {
            HelpCommand.class,
            BalanceCommand.class,
            InfoCommand.class,
            RekeyCommand.class,
            SendCommand.class,
            CreateCommand.class,
            StakeCommand.class,
            UpdateCommand.class
        },
        description = "Performs account operations")
public class AccountsCommand implements Callable<Integer> {
    @ParentCommand
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
