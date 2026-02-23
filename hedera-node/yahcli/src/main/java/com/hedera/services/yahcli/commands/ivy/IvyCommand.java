// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.ivy;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
        name = "ivy",
        subcommands = {
            CommandLine.HelpCommand.class,
            ValidationScenariosCommand.class,
        },
        description = "Execution acceptance tests")
public class IvyCommand implements Callable<Integer> {
    @CommandLine.ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(yahcli.getSpec().commandLine(), "Please specify an ivy subcommand");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
