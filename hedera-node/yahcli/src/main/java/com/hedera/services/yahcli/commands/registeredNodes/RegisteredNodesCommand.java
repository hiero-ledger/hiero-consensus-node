// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.yahcli.commands.registeredNodes;

import com.hedera.services.yahcli.Yahcli;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.ParentCommand;

@CommandLine.Command(
        name = "registeredNodes",
        subcommands = {CreateRegisteredNodeCommand.class},
        description = "Performs DAB registered-nodes operations")
public class RegisteredNodesCommand implements Callable<Integer> {
    @ParentCommand
    Yahcli yahcli;

    @Override
    public Integer call() throws Exception {
        throw new CommandLine.ParameterException(
                yahcli.getSpec().commandLine(), "Please specify a registeredNodes subcommand");
    }

    public Yahcli getYahcli() {
        return yahcli;
    }
}
